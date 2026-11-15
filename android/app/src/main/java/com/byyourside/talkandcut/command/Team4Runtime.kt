package com.byyourside.talkandcut.command

import android.content.Context
import android.system.Os
import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Qualcomm Genie C API; all handles and calls stay on one worker thread. */
class Team4Runtime(private val context: Context) {
    interface QueryCallback : Callback { fun invoke(response: Pointer?, sentenceCode: Int, userData: Pointer?) }
    interface Genie : Library {
        fun GenieDialogConfig_createFromJson(json: String, handle: PointerByReference): Int
        fun GenieDialogConfig_free(handle: Pointer): Int
        fun GenieDialog_create(config: Pointer, handle: PointerByReference): Int
        fun GenieDialog_free(handle: Pointer): Int
        fun GenieDialog_reset(handle: Pointer): Int
        fun GenieDialog_query(handle: Pointer, prompt: String, sentenceCode: Int, callback: QueryCallback, userData: Pointer?): Int
    }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "team4-npu") }.asCoroutineDispatcher()
    private var api: Genie? = null
    private var dialog: Pointer? = null
    private var cacheReady = false
    private var rewindSupported = true
    private val directory = File(context.filesDir, "team4")
    suspend fun prepare() = withContext(worker) {
        initialize()
        if (!cacheReady) {
            val prefix = context.assets.open("team4/prompt-prefix.txt").bufferedReader().use { it.readText() }
            // Populate the fixed system prefix before listening starts. This result is never executed.
            val neutral = """{"stage":"음성 촬영","previous_question":"","state":{"camera_status":"preview","voice_capture_enabled":false},"utterance":""}"""
            runQuery(prefix + neutral + "<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n")
        }
    }
    private fun initialize() {
        if (dialog != null) return
        val model = File(directory, "llama32_hf_v68.bin")
        check(model.isFile && model.length() == 1527857152L) { "Team4 모델 파일이 설치되지 않았어요" }
        directory.mkdirs()
        for (name in listOf("tokenizer.json", "libQnnHtpV68Skel.so", "htp_backend_ext_config.json")) {
            val file = File(directory, name)
            if (!file.exists()) context.assets.open("team4/$name").use { input -> file.outputStream().use(input::copyTo) }
        }
        val libraries = context.applicationInfo.nativeLibraryDir
        Os.setenv("ADSP_LIBRARY_PATH", "${directory.absolutePath};$libraries;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp", true)
        System.setProperty("jna.nounpack", "true")
        val genie = Native.load(File(libraries, "libGenie.so").absolutePath, Genie::class.java,
            mapOf(Library.OPTION_STRING_ENCODING to "UTF-8"))
        val root = JSONObject(context.assets.open("team4/genie_config_npu.json").bufferedReader().use { it.readText() })
        val config = root.getJSONObject("dialog")
        config.getJSONObject("tokenizer").put("path", File(directory, "tokenizer.json").absolutePath)
        val engine = config.getJSONObject("engine")
        engine.getJSONObject("backend").put("extensions", File(directory, "htp_backend_ext_config.json").absolutePath)
        engine.getJSONObject("model").getJSONObject("binary").getJSONArray("ctx-bins").put(0, model.absolutePath)
        val configHandle = PointerByReference()
        check(genie.GenieDialogConfig_createFromJson(root.toString(), configHandle) == 0) { "Genie 설정 생성 실패" }
        try {
            val handle = PointerByReference()
            val status = genie.GenieDialog_create(configHandle.value, handle)
            check(status == 0 && handle.value != null) { "Team4 NPU 초기화 실패 ($status)" }
            dialog = handle.value
            api = genie
            android.util.Log.i("Team4Npu", "Genie dialog ready: Team4 W8A16, QnnHtp v68")
        } finally { genie.GenieDialogConfig_free(configHandle.value) }
    }
    suspend fun query(prompt: String): String = withContext(worker) {
        initialize()
        runQuery(prompt)
    }
    private fun runQuery(prompt: String): String {
        val genie = api!!
        val handle = dialog!!
        var reuse = cacheReady && rewindSupported
        if (!reuse) check(genie.GenieDialog_reset(handle) == 0) { "모델 문맥 초기화 실패" }
        val result = StringBuilder()
        val started = android.os.SystemClock.elapsedRealtime()
        var firstTokenMs: Long? = null
        val callback = object : QueryCallback {
            override fun invoke(response: Pointer?, sentenceCode: Int, userData: Pointer?) {
                response?.getString(0, "UTF-8")?.takeIf { it.isNotEmpty() }?.let {
                    synchronized(result) {
                        if (firstTokenMs == null) firstTokenMs = android.os.SystemClock.elapsedRealtime() - started
                        result.append(it)
                    }
                }
            }
        }
        // Genie SENTENCE_REWIND matches the complete new prompt against resident KV state,
        // discards the old request/response suffix, and prefills only the changed suffix.
        // Always send the full prompt: plain continuation would retain stale camera state.
        cacheReady = false
        var status = genie.GenieDialog_query(handle, prompt, if (reuse) 5 else 0, callback, null)
        if (status != 0 && reuse) {
            android.util.Log.w("Team4Npu", "KV rewind rejected ($status); retrying full prompt")
            rewindSupported = false
            reuse = false
            result.clear(); firstTokenMs = null
            check(genie.GenieDialog_reset(handle) == 0) { "모델 문맥 초기화 실패" }
            status = genie.GenieDialog_query(handle, prompt, 0, callback, null)
        }
        android.util.Log.i("Team4Npu", "NPU query status=$status cache=$reuse firstTokenMs=$firstTokenMs elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
        check(status == 0) { "Team4 추론 실패 ($status)" }
        cacheReady = true
        return result.toString().trim()
    }
    fun close() {
        worker.executor.execute {
            dialog?.let { api?.GenieDialog_free(it) }
            dialog = null; api = null; cacheReady = false
            worker.close()
        }
    }
}
