package com.byyourside.talkandcut.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

/**
 * SpeechRecognizer(ko-KR)를 웹 SpeechRecognition 모양([Recognition])으로 감싼다.
 * SpeechRecognizer는 한 문장마다 끝나므로 onResults/onError 뒤에 onEnd를 불러
 * [VoiceEngine]이 다시 듣게 한다. 메인 스레드에서만 쓴다.
 */
class AndroidRecognition(private val context: Context, private val devices: com.byyourside.talkandcut.devices.DeviceSettings) : Recognition {
    private var microphonePipe: com.byyourside.talkandcut.devices.MicrophonePipe? = null
    private fun closeMicrophone() { microphonePipe?.close(); microphonePipe = null }
    override var listener: Recognition.Listener? = null
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    /** abort 뒤에 오는 콜백(ERROR_CLIENT 등)을 무시하기 위한 세션 번호. */
    private var session = 0

    override fun start() {
        closeMicrophone()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO")
        }
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        val id = ++session
        r.setRecognitionListener(Callbacks(id))
        r.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                if (devices.state.value.microphone.isNotEmpty()) {
                    check(android.os.Build.VERSION.SDK_INT >= 33) { "마이크 선택은 Android 13 이상에서 지원해요" }
                    val pipe = com.byyourside.talkandcut.devices.MicrophonePipe().also { microphonePipe = it }
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe.start(devices))
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
                    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16000)
                }
            }
        )
    }

    /** 웹 abort(): 지금 세션을 버리고 onEnd를 보낸다. */
    override fun abort() {
        closeMicrophone()
        session++
        recognizer?.cancel()
        main.post { listener?.onEnd() }
    }

    override fun release() {
        closeMicrophone()
        session++
        recognizer?.destroy()
        recognizer = null
    }

    private inner class Callbacks(private val id: Int) : RecognitionListener {
        private val current get() = id == session

        override fun onReadyForSpeech(params: Bundle?) {
            if (current) listener?.onStart()
        }

        override fun onResults(results: Bundle?) {
            if (!current) return
            closeMicrophone()
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) listener?.onResult(text)
            listener?.onEnd()
        }

        override fun onError(error: Int) {
            if (!current) return
            closeMicrophone()
            val code = errorName(error)
            listener?.onError(code)
            // 바쁨·클라이언트 오류는 곧바로 다시 시작하면 같은 오류가 반복되니 잠깐 쉰다
            if (code == "aborted") main.postDelayed({ if (current) listener?.onEnd() }, 300) else listener?.onEnd()
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        fun isAvailable(context: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

        /** SpeechRecognizer 오류 → 웹 SpeechRecognitionErrorEvent.error 이름. */
        fun errorName(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_SERVER, 11 /* ERROR_SERVER_DISCONNECTED */ -> "network"
            SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
            12 /* ERROR_LANGUAGE_NOT_SUPPORTED */, 13 /* ERROR_LANGUAGE_UNAVAILABLE */ -> "language-not-supported"
            else -> "aborted" // ERROR_CLIENT, ERROR_RECOGNIZER_BUSY, ERROR_TOO_MANY_REQUESTS
        }
    }
}
