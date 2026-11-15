package com.byyourside.talkandcut.booth

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.byyourside.talkandcut.command.RuleInterpreter
import com.byyourside.talkandcut.frame.ResultRenderer
import com.byyourside.talkandcut.pose.PoseCatalog
import com.byyourside.talkandcut.voice.AndroidSpeechOutput
import com.byyourside.talkandcut.voice.VoiceSession
import kotlinx.coroutines.flow.StateFlow

/** 촬영 화면의 상태를 전부 들고 있는 ViewModel. 로직은 [Booth]에 있다. */
class BoothViewModel(app: Application) : AndroidViewModel(app) {
    val devices = com.byyourside.talkandcut.devices.DeviceSettings(app)
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

    private val speech = AndroidSpeechOutput(app, devices)
    val team4 = com.byyourside.talkandcut.command.Team4Interpreter(app, viewModelScope)
    val offlineSpeech = com.byyourside.talkandcut.voice.OfflineSpeechInput(app, devices, viewModelScope) { team4.awaitPreparation() }

    /** 음성: USB/AudioRecord → Silero VAD → Whisper → 명령 실행. 안내는 TextToSpeech. */
    val voice = VoiceSession(
        scope = viewModelScope,
        input = offlineSpeech,
        output = speech,
        interpreter = team4,
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
        offlineSpeech.release()
        speech.release()
        result.release()
        devices.release()
        team4.close()
    }
}
