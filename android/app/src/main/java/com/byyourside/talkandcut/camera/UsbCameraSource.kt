package com.byyourside.talkandcut.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.usb.UsbDevice
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.byyourside.talkandcut.frame.Layouts
import com.jiangdg.usb.USBMonitor
import com.jiangdg.uvc.UVCCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** Direct UVC input for boards without an Android external-camera provider. */
class UsbCameraSource(context: Context, private val device: UsbDevice) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var camera: UVCCamera? = null
    private var texture: SurfaceTexture? = null
    private var renderer: FilterRenderer? = null
    private var owner: LifecycleOwner? = null
    private var observer: LifecycleEventObserver? = null
    @Volatile private var active = false
    @Volatile private var released = false
    private var error: (String) -> Unit = {}
    private val frameLock = Any()
    private var frame: ByteArray? = null
    private var width = 640
    private var height = 480
    private val monitor = USBMonitor(context, object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice?) {}
        override fun onDetach(detached: UsbDevice?) {
            if (detached?.deviceName == device.deviceName) fail("USB 카메라 연결이 끊겼어요.")
        }
        override fun onConnect(connected: UsbDevice?, block: USBMonitor.UsbControlBlock?, createNew: Boolean) {
            if (released || connected?.deviceName != device.deviceName || block == null) return
            worker.execute {
                if (!active || released) return@execute
                var opened: UVCCamera? = null
                runCatching {
                    closeCamera()
                    val cam = UVCCamera().also { opened = it; it.open(block) }
                    val sizes = cam.getSupportedSizeList(UVCCamera.FRAME_FORMAT_MJPEG).orEmpty()
                    val size = sizes.firstOrNull { it.width == 1280 && it.height == 720 }
                        ?: sizes.firstOrNull { it.width == 640 && it.height == 480 }
                        ?: sizes.firstOrNull()
                    width = size?.width ?: 640
                    height = size?.height ?: 480
                    runCatching { cam.setPreviewSize(width, height, UVCCamera.FRAME_FORMAT_MJPEG) }
                        .getOrElse { cam.setPreviewSize(width, height, UVCCamera.FRAME_FORMAT_YUYV) }
                    val surface = texture ?: throw IllegalStateException("Preview surface is not ready")
                    surface.setDefaultBufferSize(width, height)
                    renderer?.setSource(width, height)
                    renderer?.setTransform(0, false)
                    cam.setPreviewTexture(surface)
                    cam.setFrameCallback({ bytes ->
                        if (bytes != null && active && !released) synchronized(frameLock) {
                            val sizeBytes = width * height * 4
                            if (bytes.remaining() >= sizeBytes) {
                                val target = frame?.takeIf { it.size == sizeBytes } ?: ByteArray(sizeBytes).also { frame = it }
                                bytes.get(target)
                            }
                        }
                    }, UVCCamera.PIXEL_FORMAT_RGBX)
                    cam.startPreview()
                    camera = cam
                }.onFailure {
                    runCatching { opened?.destroy() }
                    android.util.Log.e("UsbCameraSource", "USB camera open failed", it)
                    fail("USB 카메라를 열 수 없어요. 연결과 USB 접근 권한을 확인해 주세요.")
                }
            }
        }
        override fun onDisconnect(device: UsbDevice?, block: USBMonitor.UsbControlBlock?) {
            if (device?.deviceName == this@UsbCameraSource.device.deviceName && !released) {
                worker.execute { closeCamera() }
            }
        }
        override fun onCancel(device: UsbDevice?) = fail("USB 카메라 접근을 허용해 주세요.")
    })
    private fun fail(message: String) { main.post { if (!released) error(message) } }
    fun bind(owner: LifecycleOwner, onError: (String) -> Unit) {
        this.owner = owner
        error = onError
        monitor.register()
        observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) { active = true; openIfReady() }
            if (event == Lifecycle.Event.ON_STOP) { active = false; worker.execute { closeCamera() } }
        }.also { owner.lifecycle.addObserver(it) }
        active = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        openIfReady()
    }
    fun attach(renderer: FilterRenderer, texture: SurfaceTexture) {
        this.renderer = renderer
        this.texture = texture
        openIfReady()
    }
    private fun openIfReady() {
        if (active && !released && texture != null) runCatching { monitor.requestPermission(device) }
            .onFailure { fail("USB 카메라 접근을 허용해 주세요.") }
    }
    private fun closeCamera() {
        runCatching { camera?.destroy() }
        camera = null
        synchronized(frameLock) { frame = null }
    }
    suspend fun capture(aspect: Double): Bitmap? = withContext(Dispatchers.Default) {
        val bytes = synchronized(frameLock) { frame?.copyOf() } ?: return@withContext null
        runCatching {
            val src = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            src.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
            val crop = Layouts.coverCrop(width.toDouble(), height.toDouble(), aspect)
            val scale = minOf(1.0, 1440.0 / crop.h)
            val out = Bitmap.createBitmap((crop.w * scale).toInt(), (crop.h * scale).toInt(), Bitmap.Config.ARGB_8888)
            val matrix = Matrix().apply {
                postScale(-1f, 1f, width / 2f, height / 2f)
                postTranslate(-crop.x.toFloat(), -crop.y.toFloat())
                postScale(scale.toFloat(), scale.toFloat())
            }
            android.graphics.Canvas(out).drawBitmap(src, matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            src.recycle()
            out
        }.getOrNull()
    }
    fun release() {
        released = true
        active = false
        observer?.let { owner?.lifecycle?.removeObserver(it) }
        worker.execute { closeCamera(); monitor.destroy() }
        worker.shutdown()
    }
}
