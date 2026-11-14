package com.byyourside.talkandcut.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.byyourside.talkandcut.booth.BoothSounds
import com.byyourside.talkandcut.booth.CaptureSource
import com.byyourside.talkandcut.frame.Layouts
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * CameraX 전면 카메라. 미리보기는 PreviewView(거울 모드), 촬영은 ImageCapture.
 * 찍은 사진은 미리보기와 똑같이 보이도록 회전·좌우반전한 뒤 칸 비율로 가운데를 자른다(보정 전 원본).
 */
class CameraController(context: Context) : CaptureSource<Bitmap> {
    private val appContext = context.applicationContext
    private val executor = ContextCompat.getMainExecutor(appContext)

    private val resolution = ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(
            ResolutionStrategy(Size(1920, 1440), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
        )
        .build()

    val controller = LifecycleCameraController(appContext).apply {
        cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        imageCaptureMode = ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
        imageCaptureResolutionSelector = resolution
        previewResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(
                ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
            )
            .build()
        setEnabledUseCases(LifecycleCameraController.IMAGE_CAPTURE)
    }

    fun bind(owner: LifecycleOwner, view: PreviewView) {
        view.scaleType = PreviewView.ScaleType.FILL_CENTER
        view.controller = controller
        controller.bindToLifecycle(owner)
    }

    fun unbind() {
        controller.unbind()
    }

    override suspend fun capture(aspect: Double): Bitmap? = suspendCancellableCoroutine { cont ->
        controller.takePicture(executor, object : ImageCapture.OnImageCapturedCallback() {
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
class AndroidBoothSounds : BoothSounds {
    private val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
    private val shutterSound = MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }

    override fun beep() {
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
    }

    override fun shutter() {
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
    }

    fun release() {
        tone?.release()
        shutterSound.release()
    }
}
