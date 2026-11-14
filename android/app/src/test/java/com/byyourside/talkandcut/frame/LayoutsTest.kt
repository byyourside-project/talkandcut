package com.byyourside.talkandcut.frame

import org.junit.Assert.assertEquals
import org.junit.Test

/** 기대값은 웹 src/frames.js 계산 결과. */
class LayoutsTest {
    @Test
    fun gridCells() {
        val r = Layouts.cellRects(LayoutId.GRID)
        assertEquals(4, r.size)
        assertEquals(RectF(44.0, 44.0, 542.0, 724.0), r[0])
        assertEquals(RectF(614.0, 796.0, 542.0, 724.0), r[3])
        assertEquals(542.0 / 724.0, Layouts.cellAspect(LayoutId.GRID), 1e-9)
    }

    @Test
    fun stripCells() {
        val r = Layouts.cellRects(LayoutId.STRIP)
        assertEquals(4, r.size)
        assertEquals(520.0, r[0].w, 1e-9)
        assertEquals(361.0, r[0].h, 1e-9)
        assertEquals(40.0 + 3 * (361.0 + 22.0), r[3].y, 1e-9)
        assertEquals(520.0 / 361.0, Layouts.cellAspect(LayoutId.STRIP), 1e-9)
    }

    @Test
    fun coverCropCenters() {
        // 세로 3:4 영상을 1×4 가로 칸 비율로 자르면 위아래가 잘린다
        val a = Layouts.cellAspect(LayoutId.STRIP)
        val c = Layouts.coverCrop(960.0, 1280.0, a)
        assertEquals(960.0, c.w, 1e-9)
        assertEquals(960.0 / a, c.h, 1e-9)
        assertEquals(0.0, c.x, 1e-9)
        assertEquals((1280.0 - 960.0 / a) / 2, c.y, 1e-9)

        // 가로가 남으면 좌우가 잘린다
        val d = Layouts.coverCrop(1000.0, 500.0, 1.0)
        assertEquals(RectF(250.0, 0.0, 500.0, 500.0), d)
    }
}
