package com.byyourside.talkandcut.booth

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow

/** 촬영 화면의 상태를 전부 들고 있는 ViewModel. 로직은 [Booth]에 있다. */
class BoothViewModel : ViewModel() {
    private var camera: CaptureSource<Bitmap>? = null
    private var sounds: BoothSounds = BoothSounds.None

    private val booth = Booth(
        scope = viewModelScope,
        camera = { aspect -> camera?.capture(aspect) },
        sounds = object : BoothSounds {
            override fun beep() = sounds.beep()
            override fun shutter() = sounds.shutter()
        },
    )
    private val executor = CommandExecutor(booth)

    val state: StateFlow<BoothState<Bitmap>> = booth.state

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
}
