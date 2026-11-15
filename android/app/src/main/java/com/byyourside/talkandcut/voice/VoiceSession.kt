package com.byyourside.talkandcut.voice

import com.byyourside.talkandcut.booth.BoothController
import com.byyourside.talkandcut.booth.BoothState
import com.byyourside.talkandcut.booth.Command
import com.byyourside.talkandcut.booth.CommandExecutor
import com.byyourside.talkandcut.command.BoothContext
import com.byyourside.talkandcut.command.CommandInterpreter
import com.byyourside.talkandcut.command.CommandJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 듣기 → 해석 → 실행 → 안내. 웹 main.js handleUtterance + reply(speak) + Voice.say 이식.
 * 메뉴 "문장 직접 입력"도 [typed]로 같은 해석 경로를 탄다.
 *
 * 웹과 다른 점: 웹은 네 컷을 다 찍으면 마이크를 끄지만, 앱은 결과 보기에서도 계속 듣는다
 * ("프레임 추천해줘", "저장해줘"를 결과 보기에서 말로 하기 위해).
 */
class VoiceSession(
    private val scope: CoroutineScope,
    val input: SpeechInput,
    private val output: SpeechOutput,
    private val interpreter: CommandInterpreter,
    private val booth: BoothController,
    private val state: StateFlow<BoothState<*>>,
    private val announcements: Flow<String>,
) {
    private val executor = CommandExecutor(booth)
    private val _ttsEnabled = MutableStateFlow(true)
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled.asStateFlow()
    private val _lastJson = MutableStateFlow("")

    /** 마지막으로 해석한 발화와 명령(웹 lastJson). */
    val lastJson: StateFlow<String> = _lastJson.asStateFlow()

    private var speakJob: Job? = null
    private var speakSeq = 0
    private val commandMutex = Mutex()

    /** 인식 문장과 비동기 안내를 받기 시작한다. */
    fun bind() {
        scope.launch { input.transcripts.collect { if (it.isNotBlank()) handleUtterance(it) } }
        scope.launch { announcements.collect { say(it) } }
    }

    fun start() = input.start()

    fun stop() {
        cancelSpeech()
        input.stop()
    }

    /** 웹 micBtn: 듣는 중이면 끄고, 아니면 다시 연결. */
    fun toggleMic() {
        val s = input.state.value
        stop()
        if (s == VoiceState.LISTENING || s == VoiceState.SPEAKING) return
        start()
    }

    fun setTtsEnabled(enabled: Boolean) {
        _ttsEnabled.value = enabled
        if (!enabled && speakJob?.isActive == true) {
            cancelSpeech()
            input.resume()
        }
    }

    fun typed(text: String) {
        val t = text.trim()
        if (t.isNotEmpty()) scope.launch { handleUtterance(t) }
    }

    suspend fun handleUtterance(text: String) = commandMutex.withLock { handleSerialized(text) }

    private suspend fun handleSerialized(text: String) {
        val s = state.value
        if (s.shutterOn) return
        var actions = interpreter.interpret(text, BoothContext.of(s))
        val layoutChange = actions.firstOrNull { it is Command.Layout && it.layout != s.layout }
        if (layoutChange != null) {
            say(executor.execute(layoutChange))
            return
        }
        if (actions.any { it == Command.Start }) {
            actions = actions.filter { it !is Command.Capture && it !is Command.Retake }
        }
        _lastJson.value = JSON.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            buildJsonObject {
                put("heard", text)
                (interpreter as? com.byyourside.talkandcut.command.InterpreterFeedback)?.let {
                    put("interpreter", it.interpreterName)
                    put("model_output", it.rawOutput)
                }
                put("actions", JsonArray(actions.map(CommandJson::toJson)))
            },
        )
        if (actions.isEmpty()) {
            reply((interpreter as? com.byyourside.talkandcut.command.InterpreterFeedback)?.explanation
                ?: "잘 못 알아들었어요. “찍어줘”나 “따뜻하게”처럼 말해 보세요")
            return
        }
        if (actions.any { it is Command.Capture }) {
            val latest = state.value
            if (s.busy || !s.cameraOn || s.resultOpen || s.currentIndex < 0 ||
                !latest.cameraOn || latest.busy || latest.resultOpen || latest.currentIndex < 0) {
                booth.reply("카메라가 촬영 준비 상태일 때 말해 주세요.")
                return
            }
        }
        val msgs = actions.map { executor.execute(it) }.filter { it.isNotEmpty() }
        if (msgs.isNotEmpty()) {
            val takingPhoto = actions.any { it is Command.Capture || it is Command.Retake }
            val joined = msgs.joinToString(". ")
            booth.reply(joined)
            if (!takingPhoto) say(joined)
        }
    }

    private fun reply(text: String) {
        booth.reply(text)
        say(text)
    }

    /** 안내 중에는 인식을 멈춰 부스의 음성을 명령으로 듣지 않게 한다. */
    fun say(text: String) {
        if (!_ttsEnabled.value || text.isBlank()) return
        val token = ++speakSeq
        input.pause()
        speakJob?.cancel()
        output.cancel()
        speakJob = scope.launch {
            try {
                output.say(text)
            } finally {
                if (token == speakSeq) input.resume()
            }
        }
    }

    private fun cancelSpeech() {
        speakSeq++
        speakJob?.cancel()
        speakJob = null
        output.cancel()
    }

    companion object {
        private val JSON = Json { prettyPrint = true }
    }
}
