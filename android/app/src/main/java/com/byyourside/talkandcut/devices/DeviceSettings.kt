package com.byyourside.talkandcut.devices

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DeviceOption(val id: String, val label: String)
data class DeviceState(
    val cameras: List<DeviceOption> = listOf(DeviceOption("", "기본")),
    val microphones: List<DeviceOption> = listOf(DeviceOption("", "기본")),
    val speakers: List<DeviceOption> = listOf(DeviceOption("", "기본")),
    val camera: String = "", val microphone: String = "", val speaker: String = "",
)

/** Connected device inventory; selections survive activity recreation and app restarts. */
class DeviceSettings(private val context: Context) {
    val audio = context.getSystemService(AudioManager::class.java)
    private val usb = context.getSystemService(UsbManager::class.java)
    private val cameras = context.getSystemService(CameraManager::class.java)
    private val prefs = context.getSharedPreferences("devices", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(DeviceState(
        camera = prefs.getString("camera", "") ?: "",
        microphone = prefs.getString("microphone", "") ?: "",
        speaker = prefs.getString("speaker", "") ?: "",
    ))
    val state = mutable.asStateFlow()
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = refresh()
    }
    private val cameraCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) = refresh()
        override fun onCameraUnavailable(cameraId: String) = refresh()
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }
    init {
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        cameras.registerAvailabilityCallback(cameraCallback, Handler(Looper.getMainLooper()))
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }
    fun usbCameras(): List<UsbDevice> = usb.deviceList.values.filter { device ->
        (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }
    }.sortedBy { it.deviceName }
    fun usbKey(device: UsbDevice) = "usb:${device.vendorId}:${device.productId}:${device.deviceName}"
    fun usbMicrophones(): List<UsbDevice> = usb.deviceList.values.filter { device ->
        (0 until device.interfaceCount).any { i ->
            val iface = device.getInterface(i)
            iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO && iface.interfaceSubclass == 2 &&
                (0 until iface.endpointCount).any { iface.getEndpoint(it).direction == UsbConstants.USB_DIR_IN }
        }
    }.sortedBy { it.deviceName }
    fun usbMicrophone(): UsbDevice? {
        val selection = mutable.value.microphone
        val audioName = microphone()?.productName?.toString().orEmpty()
        return usbMicrophones().firstOrNull { "usbmic:${it.deviceName}" == selection ||
            (audioName.isNotEmpty() && it.productName?.let(audioName::contains) == true) }
            ?: if (selection.isEmpty()) usbMicrophones().firstOrNull() else null
    }
    fun audioKey(device: AudioDeviceInfo) = "${device.type}:${device.address}:${device.productName}"
    private fun isUsb(device: AudioDeviceInfo) = device.type in listOf(
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY,
    )
    private fun audioOptions(flag: Int): List<DeviceOption> {
        var usbNumber = 0
        return listOf(DeviceOption("", "기본")) + audio.getDevices(flag)
            .filter { it.type in setOf(AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY, AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC,
                AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE) }
            .sortedWith(compareBy({ it.type }, { it.address }, { it.id })).distinctBy(::audioKey).map { device ->
                val name = device.productName.toString()
                val prefix = when (device.type) {
                    AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI · "
                    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "유선 · "
                    else -> ""
                }
                DeviceOption(audioKey(device), if (isUsb(device)) "USB ${++usbNumber} · $name" else prefix + name)
            }
    }
    fun refresh() {
        val cameraOptions = listOf(DeviceOption("", "기본")) +
            runCatching { cameras.cameraIdList.map { DeviceOption("camera:$it", "내장/시스템 카메라 $it") } }.getOrDefault(emptyList()) +
            usbCameras().mapIndexed { index, device -> DeviceOption(usbKey(device), "USB ${index + 1} · ${device.productName ?: "웹캠"}") }
        val systemInputs = audioOptions(AudioManager.GET_DEVICES_INPUTS)
        var usbNumber = systemInputs.count { it.label.startsWith("USB ") }
        val inputs = systemInputs + usbMicrophones().filter { device ->
            systemInputs.none { device.productName?.let(it.label::contains) == true }
        }.map { DeviceOption("usbmic:${it.deviceName}", "USB ${++usbNumber} · ${it.productName ?: "마이크"}") }
        val outputs = audioOptions(AudioManager.GET_DEVICES_OUTPUTS)
        val old = mutable.value
        mutable.value = old.copy(cameras = cameraOptions, microphones = inputs, speakers = outputs,
            camera = old.camera.takeIf { id -> cameraOptions.any { it.id == id } } ?: "",
            microphone = old.microphone.takeIf { id -> inputs.any { it.id == id } } ?: "",
            speaker = old.speaker.takeIf { id -> outputs.any { it.id == id } } ?: "")
    }
    fun selectCamera(id: String) { prefs.edit().putString("camera", id).apply(); mutable.value = mutable.value.copy(camera = id) }
    fun selectMicrophone(id: String) { prefs.edit().putString("microphone", id).apply(); mutable.value = mutable.value.copy(microphone = id) }
    fun selectSpeaker(id: String) { prefs.edit().putString("speaker", id).apply(); mutable.value = mutable.value.copy(speaker = id) }
    fun microphone(): AudioDeviceInfo? = audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { audioKey(it) == mutable.value.microphone }
    fun speaker(): AudioDeviceInfo? = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { audioKey(it) == mutable.value.speaker }
    fun release() {
        audio.unregisterAudioDeviceCallback(callback)
        cameras.unregisterAvailabilityCallback(cameraCallback)
        context.unregisterReceiver(receiver)
    }
}
