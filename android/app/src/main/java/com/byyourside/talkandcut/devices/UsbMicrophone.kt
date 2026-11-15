package com.byyourside.talkandcut.devices

import android.content.Context
import android.hardware.usb.UsbDevice
import com.jiangdg.uac.UACAudio
import com.jiangdg.usb.USBMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout

/** Direct USB Audio Class capture, bypassing a board's unavailable DSP USB input path. */
class UsbMicrophone(context: Context, private val device: UsbDevice) {
    private val connected = CompletableDeferred<USBMonitor.UsbControlBlock>()
    private val data = Channel<ByteArray>(32, BufferOverflow.DROP_OLDEST)
    private var audio: UACAudio? = null
    private var initialized = false
    var sampleRate = 16000; private set
    var channels = 1; private set
    var bits = 16; private set
    private val monitor = USBMonitor(context, object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice?) {}
        override fun onDetach(device: UsbDevice?) { if (device?.deviceName == this@UsbMicrophone.device.deviceName) data.close(IllegalStateException("USB microphone disconnected")) }
        override fun onConnect(device: UsbDevice?, block: USBMonitor.UsbControlBlock?, createNew: Boolean) {
            if (device?.deviceName == this@UsbMicrophone.device.deviceName && block != null) connected.complete(block)
        }
        override fun onDisconnect(device: UsbDevice?, block: USBMonitor.UsbControlBlock?) {
            if (device?.deviceName == this@UsbMicrophone.device.deviceName) data.close()
        }
        override fun onCancel(device: UsbDevice?) { connected.completeExceptionally(SecurityException("USB microphone permission denied")) }
    })
    suspend fun start() {
        monitor.register()
        monitor.requestPermission(device)
        val block = withTimeout(15000) { connected.await() }
        val engine = UACAudio().also { audio = it }
        engine.init(block)
        check(engine.audioStatus == UACAudio.AudioStatus.CREATED) { "USB audio initialization failed" }
        initialized = true
        sampleRate = engine.sampleRate
        channels = engine.channelCount
        bits = engine.bitResolution
        check(sampleRate > 0 && channels in 1..8 && bits in listOf(8, 16)) { "Unsupported USB microphone format" }
        engine.addAudioCallBack { bytes -> if (bytes != null) data.trySend(bytes) }
        engine.startRecording()
        check(engine.audioStatus == UACAudio.AudioStatus.RUNNING) { "USB audio recording failed" }
        android.util.Log.i("OfflineSpeech", "Direct USB mic ${device.productName}: ${sampleRate}Hz ${channels}ch ${bits}bit")
    }
    suspend fun read(): ByteArray = withTimeout(4000) { data.receive() }
    fun close() {
        if (initialized) {
            runCatching { audio?.stopRecording() }
            // The vendor stop call returns before its libusb event thread has unwound.
            // Keep the context alive through that tail; releasing immediately races its mutex.
            Thread.sleep(1000)
            runCatching { audio?.release() }
        }
        audio = null
        data.close()
        monitor.destroy()
    }
}
