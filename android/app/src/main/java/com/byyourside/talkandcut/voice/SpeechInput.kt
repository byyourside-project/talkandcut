package com.byyourside.talkandcut.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 웹 voice.js onState 상태 이름 그대로. */
enum class VoiceState { OFF, CONNECTING, LISTENING, SPEAKING, DENIED, UNSUPPORTED, ERROR }

/** 음성 인식 교체 지점(docs/mobile-port-plan.md 6장). 지금은 [VoiceEngine] + [AndroidRecognition]. */
interface SpeechInput {
    val state: StateFlow<VoiceState>

    /** ERROR/DENIED일 때 원인(웹 reason: timeout, start-ended, network, …). */
    val reason: StateFlow<String?>

    /** 최종 문장만. */
    val transcripts: Flow<String>

    fun start()
    fun stop()

    /** 안내 음성이 나가는 동안 듣지 않는다(부스 목소리를 명령으로 듣지 않게). */
    fun pause()
    fun resume()
}

/** 음성 출력 교체 지점. */
interface SpeechOutput {
    /** 끝날 때까지 suspend. */
    suspend fun say(text: String)
    fun cancel()
}

/**
 * 웹 SpeechRecognition과 같은 모양의 인식 세션. [VoiceEngine]이 이 위에서 재시작·타임아웃을 관리한다.
 * 콜백은 메인 스레드에서 부른다.
 */
interface Recognition {
    var listener: Listener?

    /** 권한이 없으면 SecurityException. */
    fun start()
    fun abort()
    fun release() {}

    interface Listener {
        fun onStart()
        fun onResult(transcript: String)

        /** 웹 SpeechRecognitionErrorEvent.error 이름(no-speech, aborted, not-allowed, network, …). */
        fun onError(code: String)
        fun onEnd()
    }
}
