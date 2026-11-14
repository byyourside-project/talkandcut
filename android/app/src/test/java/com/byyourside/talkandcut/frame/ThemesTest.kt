package com.byyourside.talkandcut.frame

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 기대값은 웹 src/frames.js recommendFrames를 node로 돌린 결과. */
class ThemesTest {
    private data class Case(val rgb: DoubleArray?, val mood: String?, val ids: String, val scores: List<Double>, val top: List<String>)

    private val cases = listOf(
        Case(doubleArrayOf(0.6, 0.5, 0.4), "warm", "peach,brick,forest,lemon,white,black,film,sky,midnight,lavender",
            listOf(5.0, 5.0, 3.0, 2.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.0), listOf("지금 보정한 분위기와 같은 계열", "사진의 주황빛 톤과 같은 색 계열")),
        Case(doubleArrayOf(0.8, 0.8, 0.82), null, "black,film,white,forest,midnight,peach,lavender,sky,lemon,brick",
            listOf(3.5, 3.5, 2.0, 1.5, 1.5, 0.0, 0.0, 0.0, 0.0, 0.0), listOf("밝은 사진이 어두운 바탕에서 돋보여요", "흑백·저채도 사진엔 무채색 프레임")),
        Case(doubleArrayOf(0.2, 0.25, 0.5), "dreamy", "lavender,midnight,sky,white,lemon,peach,black,film,forest,brick",
            listOf(6.5, 5.0, 3.5, 2.5, 2.5, 1.5, 1.0, 1.0, 0.0, 0.0), listOf("지금 보정한 분위기와 같은 계열", "사진의 푸른 톤과 같은 색 계열")),
        Case(doubleArrayOf(0.3, 0.3, 0.3), "mono", "black,film,white,peach,lavender,sky,lemon,forest,brick,midnight",
            listOf(5.0, 5.0, 3.5, 1.5, 1.5, 1.5, 1.5, 0.0, 0.0, 0.0), listOf("지금 보정한 분위기와 같은 계열", "흑백·저채도 사진엔 무채색 프레임")),
        Case(null, null, "white,black,film,peach,lavender,sky,lemon,forest,brick,midnight",
            List(10) { 0.0 }, listOf("무난하게 어울리는 기본 프레임")),
        Case(doubleArrayOf(0.9, 0.3, 0.3), "happy", "peach,white,sky,lemon,black,film,brick,forest,midnight,lavender",
            listOf(5.0, 4.0, 4.0, 3.0, 2.5, 2.5, 2.0, 1.5, 1.5, 0.0), listOf("지금 보정한 분위기와 같은 계열", "사진의 붉은 톤과 같은 색 계열")),
        Case(doubleArrayOf(0.2, 0.6, 0.3), null, "forest,white,black,film,peach,lavender,sky,lemon,brick,midnight",
            listOf(2.0, 1.0, 1.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0), listOf("사진의 초록빛 톤과 같은 색 계열")),
    )

    @Test
    fun recommendFramesMatchesWeb() {
        for (c in cases) {
            val r = Frames.recommendFrames(c.rgb, c.mood)
            val label = "${c.rgb?.toList()} ${c.mood}"
            assertEquals(label, c.ids, r.joinToString(",") { it.themeId })
            assertEquals(label, c.scores, r.map { it.score })
            assertEquals(label, c.top, r[0].reasons)
        }
    }

    /** tests/frames.test.mjs "프레임 추천은 촬영 때 선택한 모양을 덮어쓰지 않는다" */
    @Test
    fun everyRecHasReasonsAndNoLayout() {
        val frames = Frames.recommendFrames(doubleArrayOf(0.6, 0.5, 0.4), "warm")
        assertEquals(THEMES.size, frames.size)
        assertTrue(frames.all { it.reasons.isNotEmpty() && it.reasons.size <= 2 })
    }

    @Test
    fun rgbToHslMatchesWeb() {
        assertArrayEquals(doubleArrayOf(30.0, 0.2, 0.5), Frames.rgbToHsl(doubleArrayOf(0.6, 0.5, 0.4)), 1e-9)
        assertArrayEquals(doubleArrayOf(135.0, 0.5, 0.4), Frames.rgbToHsl(doubleArrayOf(0.2, 0.6, 0.3)), 1e-9)
        assertEquals("white", themeById("nope").id)
    }
}
