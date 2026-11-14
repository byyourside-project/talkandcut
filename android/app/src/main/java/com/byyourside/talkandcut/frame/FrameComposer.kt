package com.byyourside.talkandcut.frame

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.RectF as GRectF
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * 웹 src/frames.js composite·drawPattern·fitText 이식 (Canvas 2D → android.graphics.Canvas).
 * cells는 이미 보정이 적용된 칸 이미지(크기 = 칸 크기 반올림). null이면 빈 칸 번호를 그린다.
 */
class FrameComposer(assets: AssetManager) {
    private val display: Typeface = runCatching { Typeface.createFromAsset(assets, "fonts/BlackHanSans-Regular.ttf") }
        .getOrDefault(Typeface.DEFAULT_BOLD)
    private val brand700 = grotesk(assets, 700)
    private val brand600 = grotesk(assets, 600)

    private fun grotesk(assets: AssetManager, weight: Int): Typeface = runCatching {
        Typeface.Builder(assets, "fonts/SpaceGrotesk.ttf").setFontVariationSettings("'wght' $weight").build()
    }.getOrNull() ?: Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

    fun composite(
        layout: LayoutId,
        theme: Theme,
        cells: List<Bitmap?>,
        caption: String,
        date: LocalDate = LocalDate.now(),
    ): Bitmap {
        val l = Layouts.LAYOUTS.getValue(layout)
        val out = Bitmap.createBitmap(l.w, l.h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val bg = color(theme.bg)
        val ink = color(theme.ink)
        c.drawColor(bg)
        drawPattern(c, theme, l.w.toFloat(), l.h.toFloat(), ink)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        Layouts.cellRects(layout).forEachIndexed { i, r ->
            val rect = GRectF(r.x.toFloat(), r.y.toFloat(), (r.x + r.w).toFloat(), (r.y + r.h).toFloat())
            c.save()
            c.clipPath(Path().apply { addRoundRect(rect, 6f, 6f, Path.Direction.CW) })
            val shot = cells.getOrNull(i)
            if (shot != null) {
                paint.alpha = 255
                c.drawBitmap(shot, null, rect, paint)
            } else {
                paint.color = ink
                paint.alpha = (0.12f * 255).roundToInt()
                c.drawRect(rect, paint)
                val text = textPaint(display, (r.h * 0.28).roundToInt().toFloat(), ink, 0.45f)
                drawMiddle(c, (i + 1).toString(), rect.centerX(), rect.centerY(), text)
            }
            c.restore()
        }

        // 인화지 하단에 부스 이름과 촬영 날짜를 남긴다.
        val grid = layout == LayoutId.GRID
        val baseY = (l.h - l.bottom).toFloat()
        val cx = l.w / 2f
        if (caption.isNotEmpty()) {
            val p = textPaint(display, if (grid) 56f else 42f, ink, 1f)
            fitText(c, caption, cx, baseY + l.bottom * 0.23f, (l.w - l.margin * 2).toFloat(), p)
        }
        val brandY = baseY + l.bottom * (if (caption.isNotEmpty()) 0.52f else 0.43f)
        drawMiddle(c, "Talk & Cut", cx, brandY, textPaint(brand700, if (grid) 64f else 51f, ink, 1f))
        val d = "%d.%02d.%02d".format(date.year, date.monthValue, date.dayOfMonth)
        drawMiddle(c, d, cx, brandY + l.bottom * 0.24f, textPaint(brand600, if (grid) 28f else 23f, ink, 0.8f))
        return out
    }

    private fun drawPattern(c: Canvas, theme: Theme, w: Float, h: Float, ink: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; alpha = (0.18f * 255).roundToInt() }
        when (theme.pattern) {
            Pattern.DOTS -> {
                var y = 18f
                while (y < h) {
                    // 웹 (y / 36) % 2 ? 18 : 0 — y가 항상 18+36k라 실제로는 늘 18에서 시작한다
                    var x = if ((y / 36f) % 2f != 0f) 18f else 0f
                    while (x < w) { c.drawCircle(x, y, 4f, p); x += 36f }
                    y += 36f
                }
            }
            Pattern.STRIPES -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = 14f
                var x = -h
                while (x < w) { c.drawLine(x, 0f, x + h, h, p); x += 48f }
            }
            Pattern.CHECK -> {
                val s = 40
                for (y in 0 until h.toInt() step s) for (x in 0 until w.toInt() step s) {
                    if (((x + y) / s) % 2 == 0) c.drawRect(x.toFloat(), y.toFloat(), (x + s).toFloat(), (y + s).toFloat(), p)
                }
            }
            Pattern.SPARKLE -> {
                var seed = 7.0
                fun rnd(): Double { seed = (seed * 9301 + 49297) % 233280; return seed / 233280 }
                p.alpha = (0.35f * 255).roundToInt()
                val n = w * h / 9000
                var i = 0
                while (i < n) {
                    val x = (rnd() * w).toFloat()
                    val y = (rnd() * h).toFloat()
                    val r = (2 + rnd() * 6).toFloat()
                    val star = Path().apply {
                        moveTo(x, y - r); lineTo(x + r * 0.3f, y - r * 0.3f); lineTo(x + r, y)
                        lineTo(x + r * 0.3f, y + r * 0.3f); lineTo(x, y + r); lineTo(x - r * 0.3f, y + r * 0.3f)
                        lineTo(x - r, y); lineTo(x - r * 0.3f, y - r * 0.3f); close()
                    }
                    c.drawPath(star, p)
                    i++
                }
            }
            Pattern.FILM, Pattern.NONE -> {}
        }
        if (theme.pattern == Pattern.FILM) {
            val hole = Paint().apply { color = color("#F4EBD9"); alpha = (0.85f * 255).roundToInt() }
            val holeW = 14f
            val holeH = 22f
            var y = 12f
            while (y < h - 20) {
                c.drawRect(10f, y, 10f + holeW, y + holeH, hole)
                c.drawRect(w - 10 - holeW, y, w - 10, y + holeH, hole)
                y += 40f
            }
        }
    }

    private fun textPaint(face: Typeface, size: Float, ink: Int, alpha: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = face
        textSize = size
        color = ink
        this.alpha = (alpha * 255).roundToInt()
        textAlign = Paint.Align.CENTER
    }

    /** Canvas textBaseline = 'middle'에 맞춰 세로 가운데에 그린다. */
    private fun drawMiddle(c: Canvas, text: String, x: Float, y: Float, p: Paint) {
        val fm = p.fontMetrics
        c.drawText(text, x, y - (fm.ascent + fm.descent) / 2, p)
    }

    private fun fitText(c: Canvas, text: String, x: Float, y: Float, maxW: Float, p: Paint) {
        var size = p.textSize
        while (p.measureText(text) > maxW && size > 18) {
            size -= 2
            p.textSize = size
        }
        drawMiddle(c, text, x, y, p)
    }

    private fun color(hex: String): Int = android.graphics.Color.parseColor(hex)
}
