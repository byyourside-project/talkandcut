package com.byyourside.talkandcut.frame

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.byyourside.talkandcut.booth.BoothState
import com.byyourside.talkandcut.booth.FrameTools
import com.byyourside.talkandcut.filter.FilterParams
import com.byyourside.talkandcut.filter.OffscreenFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * 결과물 만들기: 칸마다 원본 컷에 보정을 다시 적용(오프스크린 GL)하고 프레임에 합성한다.
 * 웹 composite()에서 renderer로 칸을 그리는 부분 + save()의 다운로드를 MediaStore 저장으로.
 */
class ResultRenderer(private val context: Context) : FrameTools<Bitmap> {
    private val filter = OffscreenFilter()
    private val composer = FrameComposer(context.assets)

    /** neutral이면 보정 전 원본으로(결과 보기의 "보정 전" 비교). */
    suspend fun render(state: BoothState<Bitmap>, neutral: Boolean = false): Bitmap {
        val params = if (neutral) FilterParams.NEUTRAL else state.params
        val cells = Layouts.cellRects(state.layout).mapIndexed { i, r ->
            state.shots.getOrNull(i)?.let { shot ->
                filter.render(shot, r.w.roundToInt(), r.h.roundToInt(), params, seed = i * 3.1f)
            }
        }
        return withContext(Dispatchers.Default) {
            composer.composite(state.layout, themeById(state.themeId), cells, state.caption).also {
                cells.forEach { c -> c?.recycle() }
            }
        }
    }

    override suspend fun averageColor(shots: List<Bitmap?>, params: FilterParams): DoubleArray? =
        filter.averageColor(shots, params)

    override suspend fun save(state: BoothState<Bitmap>): Boolean = runCatching {
        val image = render(state)
        withContext(Dispatchers.IO) { writeToGallery(image) }.also { image.recycle() }
    }.getOrDefault(false)

    /** Pictures/TalkAndCut/talkandcut-yyyyMMdd-HHmm.png (웹 다운로드 파일 이름과 같음). */
    private fun writeToGallery(image: Bitmap): Boolean {
        val d = LocalDateTime.now()
        val name = "talkandcut-%04d%02d%02d-%02d%02d.png".format(d.year, d.monthValue, d.dayOfMonth, d.hour, d.minute)
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/TalkAndCut")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        val ok = runCatching {
            resolver.openOutputStream(uri)?.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: false
        }.getOrDefault(false)
        if (!ok) {
            resolver.delete(uri, null, null)
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        }
        return true
    }

    fun release() = filter.release()
}
