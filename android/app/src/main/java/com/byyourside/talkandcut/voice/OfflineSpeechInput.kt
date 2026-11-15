package com.byyourside.talkandcut.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.byyourside.talkandcut.devices.DeviceSettings
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.WaveReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.sqrt

/** Korean ASR entirely inside the app: selected USB/AudioRecord input -> VAD -> Whisper. */
class OfflineSpeechInput(
    private val context: Context,
    private val devices: DeviceSettings,
    private val scope: CoroutineScope,
    private val beforeModelLoad: suspend () -> Unit = {},
) : SpeechInput {
    private val mutableState = MutableStateFlow(VoiceState.OFF)
    override val state = mutableState.asStateFlow()
    private val mutableReason = MutableStateFlow<String?>(null)
    override val reason = mutableReason.asStateFlow()
    private val mutableTranscripts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val transcripts = mutableTranscripts.asSharedFlow()
    private val mutableLevel = MutableStateFlow(0f)
    val level = mutableLevel.asStateFlow()
    private val mutableDevice = MutableStateFlow("")
    val inputDevice = mutableDevice.asStateFlow()
    private val mutableLastText = MutableStateFlow("")
    val lastText = mutableLastText.asStateFlow()
    private val mutableDecoding = MutableStateFlow(false)
    val decoding = mutableDecoding.asStateFlow()
    private val mutableHearing = MutableStateFlow(false)
    val hearing = mutableHearing.asStateFlow()
    private val captureMutex = Mutex()
    private val worker = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    // The UAC binary retains its creating JNIEnv. Every native lifecycle call must
    // remain on the same OS thread, including after suspended reads and cancellation.
    private val captureWorker = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "usb-microphone") }.asCoroutineDispatcher()
    // Native recognizer is accessed only on worker and retained between utterances.
    private var recognizer: OfflineRecognizer? = null
    private val decodingBusy = AtomicBoolean(false)
    private val recorder = AtomicReference<AudioRecord?>(null)
    private var recordingJob: Job? = null
    @Volatile private var generation = 0L
    private var active = false
    private var paused = false
    private var released = false
    @Volatile private var audioProbe: AudioProbe? = null
    fun beginAudioProbe() { audioProbe = AudioProbe(context); Log.i(TAG, "Audio probe armed for 6.5 seconds") }

    private fun emit(state: VoiceState, reason: String? = null) {
        mutableReason.value = reason
        mutableState.value = state
    }
    private suspend fun model(): OfflineRecognizer = withContext(worker) {
        recognizer ?: OfflineRecognizer(context.assets, OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "speech/whisper-small/small-encoder.int8.onnx",
                    decoder = "speech/whisper-small/small-decoder.int8.onnx",
                    language = "ko", task = "transcribe", tailPaddings = 300,
                ),
                tokens = "speech/whisper-small/small-tokens.txt", numThreads = 4, provider = "cpu",
            ),
        )).also { recognizer = it; Log.i(TAG, "Whisper-small Korean offline model ready") }
    }

    override fun start() {
        if (released || active) return
        active = true
        if (paused) emit(VoiceState.SPEAKING) else listen()
    }
    private fun stopRecording() {
        generation++
        recordingJob?.cancel()
        recordingJob = null
        runCatching { recorder.get()?.stop() }
        mutableLevel.value = 0f
        mutableHearing.value = false
    }
    override fun stop() {
        active = false
        paused = false
        stopRecording()
        emit(VoiceState.OFF)
    }
    override fun pause() {
        paused = true
        stopRecording()
        if (active) emit(VoiceState.SPEAKING)
    }
    override fun resume() {
        if (!paused) return
        paused = false
        if (active) listen()
    }
    private fun listen() {
        val id = ++generation
        emit(VoiceState.CONNECTING, "model-loading")
        recordingJob = scope.launch {
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    throw SecurityException("RECORD_AUDIO")
                }
                beforeModelLoad()
                model()
                if (id != generation) return@launch
                withContext(captureWorker) {
                    captureMutex.withLock {
                        if (id != generation) return@withLock
                        val usb = devices.usbMicrophone()
                        if (usb != null) recordUsb(usb, id) else record(id)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (id == generation) {
                    active = false
                    Log.e(TAG, "Offline microphone/model failed", e)
                    emit(if (e is SecurityException) VoiceState.DENIED else VoiceState.ERROR,
                        if (e is SecurityException) "not-allowed" else if (recognizer == null) "model-failed" else "microphone-open-failed")
                }
            }
        }
    }
    private suspend fun recordUsb(device: android.hardware.usb.UsbDevice, id: Long) {
        val input = com.byyourside.talkandcut.devices.UsbMicrophone(context, device)
        var vad: Vad? = null
        try {
            input.start()
            val converter = PcmResampler(input.sampleRate, input.channels, input.bits)
            vad = Vad(context.assets, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
                model = "speech/silero_vad.onnx", threshold = 0.3f, minSilenceDuration = 0.10f,
                minSpeechDuration = 0.12f, maxSpeechDuration = 8f,
            )))
            if (id != generation) return
            mutableDevice.value = device.productName ?: "USB 마이크"
            emit(VoiceState.LISTENING)
            val segmenter = VoiceSegmenter(input.sampleRate)
            var lastLog = 0L
            while (kotlin.coroutines.coroutineContext.isActive && id == generation) {
                val bytes = input.read()
                val samples = converter.convert(bytes)
                val original = PcmDecoder.mono(bytes, input.channels, input.bits)
                if (samples.isEmpty()) continue
                mutableLevel.value = (sqrt(samples.sumOf { (it * it).toDouble() } / samples.size) * 8).toFloat().coerceIn(0f, 1f)
                if (SystemClock.elapsedRealtime() - lastLog > 2000) {
                    lastLog = SystemClock.elapsedRealtime()
                    Log.d(TAG, "Direct USB input level=${mutableLevel.value}")
                }
                val probe = audioProbe
                if (probe != null) {
                    if (probe.accept(bytes, samples, input.sampleRate, input.channels, input.bits)) audioProbe = null
                } else acceptSamples(samples, vad, segmenter, id, original, input.sampleRate)
            }
        } finally { input.close(); vad?.release() }
    }
    @SuppressLint("MissingPermission")
    private suspend fun record(id: Long) {
        val selected = devices.microphone()
        check(devices.state.value.microphone.isEmpty() || selected != null) { "Selected microphone disconnected" }
        val bufferSize = maxOf(8192, AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val audio = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(bufferSize).build()
        var vad: Vad? = null
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord initialization failed" }
            check(audio.setPreferredDevice(selected)) { "Microphone routing rejected" }
            vad = Vad(context.assets, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
                model = "speech/silero_vad.onnx", threshold = 0.3f, minSilenceDuration = 0.10f, minSpeechDuration = 0.12f,
                maxSpeechDuration = 8f,
            )))
            if (id != generation) return
            recorder.set(audio)
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            emit(VoiceState.LISTENING)
            Log.i(TAG, "Microphone recording started, preferred=${selected?.productName ?: "default"}")
            val shorts = ShortArray(512)
            val segmenter = VoiceSegmenter()
            var position = 0
            var lastRouteCheck = 0L
            while (kotlin.coroutines.coroutineContext.isActive && id == generation) {
                val count = audio.read(shorts, position, shorts.size - position, AudioRecord.READ_BLOCKING)
                check(count > 0) { "AudioRecord read failed: $count" }
                position += count
                if (position < shorts.size) continue
                position = 0
                val samples = FloatArray(shorts.size) { shorts[it] / 32768f }
                mutableLevel.value = (sqrt(samples.sumOf { (it * it).toDouble() } / samples.size) * 8).toFloat().coerceIn(0f, 1f)
                if (SystemClock.elapsedRealtime() - lastRouteCheck > 1000) {
                    lastRouteCheck = SystemClock.elapsedRealtime()
                    val route = audio.routedDevice
                    if (route != null) {
                        check(selected == null || selected.id == route.id) { "Requested microphone was not routed" }
                        mutableDevice.value = route.productName.toString()
                        Log.d(TAG, "Input route=${route.productName}, id=${route.id}, level=${mutableLevel.value}")
                    }
                }
                acceptSamples(samples, vad, segmenter, id)
            }
        } finally {
            recorder.compareAndSet(audio, null)
            runCatching { audio.stop() }
            audio.release()
            vad?.release()
        }
    }
    private fun acceptSamples(samples: FloatArray, vad: Vad, segmenter: VoiceSegmenter, id: Long,
                              recognitionSamples: FloatArray = samples, sampleRate: Int = RATE) {
        val segment = prepareSegment(samples, vad, segmenter, recognitionSamples)
        mutableHearing.value = segmenter.speaking
        if (segment != null && id == generation) {
            Log.i(TAG, "Speech segment queued: ${segment.size.toFloat() / sampleRate} seconds at ${sampleRate}Hz")
            decode(segment, id, sampleRate)
        }
    }
    private fun prepareSegment(samples: FloatArray, vad: Vad, segmenter: VoiceSegmenter,
                               recognitionSamples: FloatArray): FloatArray? {
        if (samples.isEmpty()) return null
        val rms = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size).toFloat()
        val gain = (0.05f / maxOf(rms, 0.001f)).coerceIn(1f, 6f)
        vad.acceptWaveform(FloatArray(samples.size) { (samples[it] * gain).coerceIn(-1f, 1f) })
        val segment = segmenter.accept(recognitionSamples, vad.isSpeechDetected())
        while (!vad.empty()) vad.pop()
        return segment
    }
    private fun decode(samples: FloatArray, id: Long, sampleRate: Int = RATE) {
        // Bound inference to one segment so speech cannot build an unbounded queue.
        if (!decodingBusy.compareAndSet(false, true)) return
        scope.launch {
            mutableDecoding.value = true
            try {
                val text = withContext(worker) {
                    val model = recognizer ?: return@withContext ""
                    val stream = model.createStream()
                    try {
                        // Keep USB PCM at its real rate. The runtime's own band-limited
                        // resampler is the reference path verified with the user's recording.
                        stream.acceptWaveform(samples, sampleRate)
                        val started = SystemClock.elapsedRealtime()
                        model.decode(stream)
                        val result = model.getResult(stream).text
                        Log.i(TAG, "Offline decode finished in ${SystemClock.elapsedRealtime() - started} ms")
                        KoreanTranscript.clean(result)
                    } finally { stream.release() }
                }
                if (id == generation && text.isNotBlank()) {
                    mutableLastText.value = text
                    Log.i(TAG, "Speech transcript delivered (${text.length} characters)")
                    mutableTranscripts.emit(text)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Log.e(TAG, "Offline inference failed", e)
                if (id == generation) { active = false; stopRecording(); emit(VoiceState.ERROR, "model-failed") }
            } finally { decodingBusy.set(false); mutableDecoding.value = false }
        }
    }
    /** Debug builds only call this to verify the real bundled decoder with a PCM WAV. */
    fun benchmarkWave(path: String) {
        stop()
        scope.launch {
            try {
                val model = model()
                withContext(worker) {
                    val wave = WaveReader.readWave(path)
                    val original = model.config
                    try {
                        for (padding in listOf(1000, 300, 1000, 300)) {
                            model.setConfig(original.copy(modelConfig = original.modelConfig.copy(
                                whisper = original.modelConfig.whisper.copy(tailPaddings = padding))))
                            val stream = model.createStream()
                            try {
                                val started = SystemClock.elapsedRealtime()
                                stream.acceptWaveform(wave.samples, wave.sampleRate)
                                model.decode(stream)
                                val elapsed = SystemClock.elapsedRealtime() - started
                                Log.i(TAG, "ASR benchmark file=$path padding=$padding elapsedMs=$elapsed text=${KoreanTranscript.clean(model.getResult(stream).text)}")
                            } finally { stream.release() }
                        }
                    } finally { model.setConfig(original) }
                }
            } catch (e: Exception) { Log.e(TAG, "ASR benchmark failed", e) }
        }
    }

    /** Debug builds only call this to verify the real bundled decoder with a PCM WAV. */
    fun verifyWave(path: String, onResult: ((String) -> Unit)? = null, pipeline: Boolean = false) {
        scope.launch {
            try {
                model()
                val result = withContext(worker) {
                    val wave = WaveReader.readWave(path)
                    val model = recognizer!!
                    val segments = if (!pipeline) listOf(wave.samples) else {
                        val found = mutableListOf<FloatArray>()
                        val vad = Vad(context.assets, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
                            model = "speech/silero_vad.onnx", threshold = 0.3f, minSilenceDuration = 0.10f,
                            minSpeechDuration = 0.12f,
                        )))
                        val segmenter = VoiceSegmenter(wave.sampleRate)
                        val converter = PcmResampler(wave.sampleRate, 1, 16)
                        val padded = wave.samples + FloatArray(wave.sampleRate)
                        try {
                            val frameSize = wave.sampleRate / 100
                            for (offset in padded.indices step frameSize) {
                                val original = padded.copyOfRange(offset, minOf(offset + frameSize, padded.size))
                                val pcm = java.nio.ByteBuffer.allocate(original.size * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                original.forEach { pcm.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
                                prepareSegment(converter.convert(pcm.array()), vad, segmenter, original)?.let(found::add)
                            }
                        } finally { vad.release() }
                        found
                    }
                    segments.map { samples ->
                        val stream = model.createStream()
                        try {
                            val started = SystemClock.elapsedRealtime()
                            stream.acceptWaveform(samples, wave.sampleRate)
                            model.decode(stream)
                            KoreanTranscript.clean(model.getResult(stream).text).also {
                                Log.i(TAG, "Verification segment seconds=${samples.size.toFloat() / wave.sampleRate} decodeMs=${SystemClock.elapsedRealtime() - started} text=$it")
                            }
                        }
                        finally { stream.release() }
                    }.filter { it.isNotBlank() }.joinToString(" / ")
                }
                Log.i(TAG, "Offline verification transcript (pipeline=$pipeline): $result")
                mutableLastText.value = result
                onResult?.invoke(result)
            } catch (e: Exception) { Log.e(TAG, "Offline verification failed", e) }
        }
    }
    fun release() {
        val captureJob = recordingJob
        stop()
        released = true
        if (captureJob != null) captureJob.invokeOnCompletion { captureWorker.close() } else captureWorker.close()
        // Queue cleanup behind in-flight native work; no concurrent deletion of native pointers.
        worker.executor.execute { recognizer?.release(); recognizer = null; worker.close() }
    }
    companion object { private const val RATE = 16000; private const val TAG = "OfflineSpeech" }
}
