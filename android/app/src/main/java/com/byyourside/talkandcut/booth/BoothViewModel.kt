package com.byyourside.talkandcut.booth

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byyourside.talkandcut.command.RuleInterpreter
import com.byyourside.talkandcut.frame.ResultRenderer
import com.byyourside.talkandcut.pose.PoseCatalog
import com.byyourside.talkandcut.voice.AndroidRecognition
import com.byyourside.talkandcut.voice.AndroidSpeechOutput
import com.byyourside.talkandcut.voice.VoiceEngine
import com.byyourside.talkandcut.voice.VoiceSession
import kotlinx.coroutines.flow.StateFlow

/** 촬영 화면의 상태를 전부 들고 있는 ViewModel. 로직은 [Booth]에 있다. */
class BoothViewModel(app: Application) : AndroidViewModel(app) {
    private var camera: CaptureSource<Bitmap>? = null
    private var sounds: BoothSounds = BoothSounds.None

    /** 결과 합성(썸네일·결과 보기·저장). */
    val result = ResultRenderer(app)

    private val booth = Booth(
        scope = viewModelScope,
        camera = { aspect -> camera?.capture(aspect) },
        poses = PoseCatalog.parse(app.assets.open("pose-catalog.json").bufferedReader().use { it.readText() }),
        sounds = object : BoothSounds {
            override fun beep() = sounds.beep()
            override fun shutter() = sounds.shutter()
        },
        frames = result,
    )
    private val executor = CommandExecutor(booth)

    val state: StateFlow<BoothState<Bitmap>> = booth.state

    private val speech = AndroidSpeechOutput(app)

    /** 음성: SpeechRecognizer → RuleInterpreter → CommandExecutor, 안내는 TextToSpeech. */
    val voice = VoiceSession(
        scope = viewModelScope,
        input = VoiceEngine(viewModelScope, AndroidRecognition.isAvailable(app), { AndroidRecognition(app) }),
        output = speech,
        interpreter = RuleInterpreter,
        booth = booth,
        state = booth.state,
        announcements = booth.announcements,
    ).also { it.bind() }

    fun attach(camera: CaptureSource<Bitmap>, sounds: BoothSounds) {
        this.camera = camera
        this.sounds = sounds
    }

    fun detach() {
        camera = null
        sounds = BoothSounds.None
        booth.setCameraOn(false)
    }

    fun setCameraOn(on: Boolean) = booth.setCameraOn(on)

    fun execute(command: Command): String = executor.execute(command)

    override fun onCleared() {
        voice.stop()
        speech.release()
        result.release()
    }
}
