package com.byyourside.talkandcut.frame

// 웹 src/frames.js의 LAYOUTS, cellRects, cellAspect, coverCrop 이식.

enum class LayoutId { STRIP, GRID }

data class Layout(
    val name: String,
    val w: Int,
    val h: Int,
    val cols: Int,
    val rows: Int,
    val margin: Int,
    val gap: Int,
    val top: Int,
    val bottom: Int,
)

data class RectF(val x: Double, val y: Double, val w: Double, val h: Double)

object Layouts {
    val LAYOUTS: Map<LayoutId, Layout> = mapOf(
        // 인생네컷 기본: 2x6인치 세로 스트립
        LayoutId.STRIP to Layout("1×4 세로 네컷", 600, 1800, 1, 4, 40, 22, 40, 250),
        // 4x6인치 2×2
        LayoutId.GRID to Layout("2×2 네컷", 1200, 1800, 2, 2, 44, 28, 44, 280),
    )

    fun cellRects(layoutId: LayoutId): List<RectF> {
        val l = LAYOUTS.getValue(layoutId)
        val cw = (l.w - l.margin * 2 - l.gap * (l.cols - 1)).toDouble() / l.cols
        val ch = (l.h - l.top - l.bottom - l.gap * (l.rows - 1)).toDouble() / l.rows
        val rects = mutableListOf<RectF>()
        for (r in 0 until l.rows) {
            for (c in 0 until l.cols) {
                rects += RectF(l.margin + c * (cw + l.gap), l.top + r * (ch + l.gap), cw, ch)
            }
        }
        return rects
    }

    fun cellAspect(layoutId: LayoutId): Double {
        val r = cellRects(layoutId)[0]
        return r.w / r.h
    }

    /** 원본 중앙에서 출력 비율만큼 잘라낼 영역. */
    fun coverCrop(width: Double, height: Double, aspect: Double): RectF {
        val cropWidth = minOf(width, height * aspect)
        val cropHeight = minOf(height, width / aspect)
        return RectF((width - cropWidth) / 2, (height - cropHeight) / 2, cropWidth, cropHeight)
    }
}
