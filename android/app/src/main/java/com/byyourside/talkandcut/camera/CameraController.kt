package com.byyourside.talkandcut.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.byyourside.talkandcut.booth.BoothSounds
import com.byyourside.talkandcut.booth.CaptureSource
import com.byyourside.talkandcut.frame.Layouts
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * CameraX 전면 카메라. 미리보기는 Preview → FilterRenderer(SurfaceTexture, 보정 셰이더),
 * 촬영은 ImageCapture. 찍은 사진은 미리보기와 똑같이 보이도록 회전·좌우반전한 뒤
 * 칸 비율로 가운데를 자른다(보정 전 원본).
 */
class CameraController(context: Context, devices: com.byyourside.talkandcut.devices.DeviceSettings, private val selection: String) : CaptureSource<Bitmap> {
    private val appContext = context.applicationContext
    private val executor = ContextCompat.getMainExecutor(appContext)
    private val providerFuture by lazy { ProcessCameraProvider.getInstance(appContext) }
    private val usbSource = devices.usbCameras().firstOrNull {
        devices.usbKey(it) == selection || (selection.isEmpty() &&
            runCatching { appContext.getSystemService(android.hardware.camera2.CameraManager::class.java).cameraIdList.isEmpty() }.getOrDefault(true))
    }?.let { UsbCameraSource(appContext, it) }

    private fun selector(w: Int, h: Int) = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(ResolutionStrategy(Size(w, h), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
        .build()

    // GL 미리보기 성능을 위해 미리보기는 960p 이하
    private val preview = Preview.Builder().setResolutionSelector(selector(1280, 960)).build()
    private val imageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
        .setResolutionSelector(selector(1920, 1440))
        .build()

    private var renderer: FilterRenderer? = null
    private var bindingGeneration = 0
    private var bound = false

    fun bind(owner: LifecycleOwner, onError: (String) -> Unit = {}) {
        usbSource?.let { it.bind(owner, onError); return }
        val generation = ++bindingGeneration
        providerFuture.addListener({
            if (generation != bindingGeneration) return@addListener
            runCatching {
                val provider = providerFuture.get()
                provider.unbindAll()
                val cameraSelector = if (selection.startsWith("camera:")) {
                    CameraSelector.Builder().addCameraFilter { cameras -> cameras.filter {
                        androidx.camera.camera2.interop.Camera2CameraInfo.from(it).cameraId == selection.removePrefix("camera:")
                    } }.build()
                } else if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    // 보드의 USB 카메라는 전면 렌즈로 분류되지 않을 수 있다.
                    CameraSelector.Builder().addCameraFilter { cameras -> cameras.take(1) }.build()
                }
                provider.bindToLifecycle(owner, cameraSelector, preview, imageCapture)
                bound = true
            }.onFailure {
                bound = false
                android.util.Log.e("CameraController", "Camera initialization failed", it)
                onError("카메라를 연결할 수 없어요. 보드의 카메라 연결을 확인한 뒤 앱을 다시 열어 주세요.")
            }
        }, executor)
    }

    fun unbind() {
        usbSource?.let { it.release(); return }
        bindingGeneration++
        bound = false
        preview.setSurfaceProvider(null)
        if (providerFuture.isDone) runCatching { providerFuture.get().unbindAll() }
        renderer = null
    }

    /** FilterRenderer가 새 SurfaceTexture를 만들 때마다 카메라 미리보기를 연결한다. */
    fun attach(renderer: FilterRenderer, st: SurfaceTexture) {
        usbSource?.let { it.attach(renderer, st); return }
        this.renderer = renderer
        preview.setSurfaceProvider(executor) { request ->
            val res = request.resolution
            st.setDefaultBufferSize(res.width, res.height)
            renderer.setSource(res.width, res.height)
            request.setTransformationInfoListener(executor) { info -> renderer.setTransform(info.rotationDegrees, info.hasCameraTransform()) }
            val surface = Surface(st)
            request.provideSurface(surface, executor) { surface.release() }
        }
    }

    override suspend fun capture(aspect: Double): Bitmap? = if (usbSource != null) usbSource.capture(aspect) else suspendCancellableCoroutine { cont ->
        if (!bound) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        imageCapture.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val result = runCatching { toShot(image, aspect) }.getOrNull()
                image.close()
                if (cont.isActive) cont.resume(result)
            }

            override fun onError(exception: ImageCaptureException) {
                if (cont.isActive) cont.resume(null)
            }
        })
    }

    private fun toShot(image: ImageProxy, aspect: Double): Bitmap {
        val src = image.toBitmap()
        val rotation = image.imageInfo.rotationDegrees
        // 회전 후 크기
        val rw = if (rotation % 180 == 0) src.width else src.height
        val rh = if (rotation % 180 == 0) src.height else src.width
        val crop = Layouts.coverCrop(rw.toDouble(), rh.toDouble(), aspect)
        val scale = minOf(1.0, MAX_SHOT_HEIGHT / crop.h)
        val outW = (crop.w * scale).roundToInt()
        val outH = (crop.h * scale).roundToInt()

        // 원본 → 회전 → 거울 → 크롭 → 축소
        val m = Matrix().apply {
            postRotate(rotation.toFloat(), src.width / 2f, src.height / 2f)
            // 회전 후 좌상단을 (0,0)으로
            val corners = floatArrayOf(0f, 0f, src.width.toFloat(), 0f, 0f, src.height.toFloat(), src.width.toFloat(), src.height.toFloat())
            mapPoints(corners)
            val minX = minOf(corners[0], corners[2], corners[4], corners[6])
            val minY = minOf(corners[1], corners[3], corners[5], corners[7])
            postTranslate(-minX, -minY)
            postScale(-1f, 1f, rw / 2f, rh / 2f)
            postTranslate(-crop.x.toFloat(), -crop.y.toFloat())
            postScale(scale.toFloat(), scale.toFloat())
        }
        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(out).drawBitmap(src, m, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }

    companion object {
        private const val MAX_SHOT_HEIGHT = 1440.0
    }
}

/** 카운트다운 비프(웹 beep(660))와 셔터음. */
class AndroidBoothSounds(private val devices: com.byyourside.talkandcut.devices.DeviceSettings) : BoothSounds {
    private val tracks = mutableSetOf<android.media.AudioTrack>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun play(frequency: Double, durationMs: Int) {
        runCatching {
            val rate = 24000
            val samples = ShortArray(rate * durationMs / 1000) { i ->
                val envelope = minOf(1.0, i / 120.0, (rate * durationMs / 1000 - i) / 120.0)
                (kotlin.math.sin(2 * Math.PI * frequency * i / rate) * 14000 * envelope).toInt().toShort()
            }
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(rate)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).build())
                .setTransferMode(android.media.AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
            tracks.add(track)
            check(track.setPreferredDevice(devices.speaker()))
            track.write(samples, 0, samples.size)
            track.play()
            handler.postDelayed({ if (tracks.remove(track)) track.release() }, durationMs + 300L)
        }
    }

    override fun beep() {
        play(660.0, 80)
    }

    override fun shutter() {
        play(1320.0, 100)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        tracks.forEach { runCatching { it.release() } }
        tracks.clear()
    }
}
