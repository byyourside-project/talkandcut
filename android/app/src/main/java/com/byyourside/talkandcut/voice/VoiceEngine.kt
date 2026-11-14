package com.byyourside.talkandcut.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 웹 src/voice.js Voice 클래스의 인식 상태 관리 이식.
 * - 한 문장이 끝나 세션이 닫히면(onEnd) 자동으로 다시 듣는다
 * - 시작 이벤트가 10초 안에 안 오면 timeout, 시작 전에 닫히면 start-ended → 자동 재시작하지 않음
 * - 지난 세션에서 늦게 온 이벤트는 무시한다(rec !== this.rec)
 * TTS는 [VoiceSession]이 맡고, 여기서는 pause/resume(웹 utterance 상태)만 다룬다.
 */
class VoiceEngine(
    private val scope: CoroutineScope,
    private val supported: Boolean,
    private val create: () -> Recognition,
    private val startTimeoutMs: Long = 10_000,
) : SpeechInput {
    private val _state = MutableStateFlow(if (supported) VoiceState.OFF else VoiceState.UNSUPPORTED)
    override val state: StateFlow<VoiceState> = _state.asStateFlow()
    private val _reason = MutableStateFlow<String?>(null)
    override val reason: StateFlow<String?> = _reason.asStateFlow()
    private val _transcripts = MutableSharedFlow<String>(extraBufferCapacity = 16)
    override val transcripts: Flow<String> = _transcripts

    private var active = false
    private var running = false
    private var pausing = false

    /** 안내 음성 중(웹 this.utterance != null). */
    private var speaking = false
    private var rec: Recognition? = null
    private var startTimer: Job? = null

    private fun emit(s: VoiceState, reason: String? = null) {
        _reason.value = reason
        _state.value = s
    }

    private fun createRecognition(): Recognition {
        val r = create()
        r.listener = object : Recognition.Listener {
            override fun onStart() {
                if (rec !== r || !active || speaking || pausing) return
                clearStartTimer()
                emit(VoiceState.LISTENING)
            }

            override fun onResult(transcript: String) {
                if (rec !== r || !active || speaking || pausing) return
                _transcripts.tryEmit(transcript.trim())
            }

            override fun onError(code: String) {
                if (rec !== r || !active || code == "no-speech" || code == "aborted") return
                fail(code)
            }

            override fun onEnd() {
                if (rec !== r) return
                val endedBeforeStart = startTimer != null && !pausing
                running = false
                pausing = false
                clearStartTimer()
                if (endedBeforeStart) {
                    fail("start-ended")
                    return
                }
                listen()
            }
        }
        return r
    }

    override fun start() {
        if (!supported) {
            emit(VoiceState.UNSUPPORTED)
            return
        }
        if (active) return
        active = true
        if (speaking) emit(VoiceState.SPEAKING)
        listen()
    }

    private fun listen() {
        if (!active || running || speaking) return
        try {
            val r = rec ?: createRecognition().also { rec = it }
            running = true
            startTimer = scope.launch { delay(startTimeoutMs); startTimer = null; fail("timeout") }
            emit(VoiceState.CONNECTING)
            r.start()
        } catch (e: SecurityException) {
            fail("not-allowed")
        } catch (e: Exception) {
            fail("start-failed")
        }
    }

    private fun clearStartTimer() {
        startTimer?.cancel()
        startTimer = null
    }

    private fun fail(reason: String) {
        active = false
        releaseRecognition()
        emit(if (reason == "not-allowed") VoiceState.DENIED else VoiceState.ERROR, reason)
    }

    override fun stop() {
        active = false
        speaking = false
        releaseRecognition()
        emit(if (supported) VoiceState.OFF else VoiceState.UNSUPPORTED)
    }

    private fun releaseRecognition() {
        val r = rec
        val wasRunning = running
        clearStartTimer()
        rec = null
        running = false
        pausing = false
        if (wasRunning) runCatching { r?.abort() }
        r?.release()
    }

    private fun pauseListening() {
        clearStartTimer()
        if (!running || pausing) return
        pausing = true
        try { rec?.abort() } catch (e: Exception) { releaseRecognition() }
    }

    private fun resumeListening() {
        if (active && pausing) {
            startTimer = scope.launch { delay(startTimeoutMs); startTimer = null; fail("timeout") }
            emit(VoiceState.CONNECTING)
        }
        listen()
    }

    /** 웹 say() 앞부분: 안내가 시작되면 듣기를 멈춘다. */
    override fun pause() {
        speaking = true
        if (active) {
            emit(VoiceState.SPEAKING)
            pauseListening()
        }
    }

    /** 웹 utterance onend: 안내가 끝나면 (마이크 세션이 닫힌 뒤) 다시 듣는다. */
    override fun resume() {
        if (!speaking) return
        speaking = false
        resumeListening()
    }
}
