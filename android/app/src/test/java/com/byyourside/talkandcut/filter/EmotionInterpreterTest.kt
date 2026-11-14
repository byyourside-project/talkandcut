package com.byyourside.talkandcut.filter

import com.byyourside.talkandcut.filter.EmotionInterpreter.applyMood
import com.byyourside.talkandcut.filter.EmotionInterpreter.explain
import com.byyourside.talkandcut.filter.EmotionInterpreter.interpretMood
import com.byyourside.talkandcut.filter.EmotionInterpreter.nearestMood
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 웹 tests/intent.test.mjs 보정 케이스 + emotion.js 동작. */
class EmotionInterpreterTest {
    private fun mood(text: String, hasCurrent: Boolean = false) = interpretMood(text, hasCurrent)!!

    @Test
    fun setVsAdjust() {
        val set = mood("몽환적인 느낌으로 해줘")
        assertEquals(MoodMode.SET, set.mode)
        assertEquals(listOf("dreamy"), set.moodIds)

        val adj = mood("조금 더 따뜻하게", hasCurrent = true)
        assertEquals(MoodMode.ADJUST, adj.mode)
        val w = adj.deltas.getValue(Param.WARMTH)
        assertTrue(w in 1..34)

        val less = mood("아까보다 덜 따뜻하게", hasCurrent = true)
        assertTrue(less.deltas.getValue(Param.WARMTH) < 0)

        val dark = mood("좀 더 어둡게", hasCurrent = true)
        assertEquals(MoodMode.ADJUST, dark.mode)
        assertTrue(dark.deltas.getValue(Param.BRIGHTNESS) < 0)
    }

    @Test
    fun multiTurn() {
        var p = applyMood(FilterParams.NEUTRAL, mood("따뜻하게"))
        val w1 = p[Param.WARMTH]
        p = applyMood(p, mood("조금 더 따뜻하게", hasCurrent = true))
        assertTrue(p[Param.WARMTH] > w1)
        p = applyMood(p, mood("흑백으로"))
        assertEquals(-100, p[Param.SATURATION])
        assertEquals(0, p[Param.WARMTH])
    }

    @Test
    fun retroFilmMetaphor() {
        assertEquals(listOf("retro"), mood("90년대 필름카메라처럼").moodIds)
    }

    @Test
    fun resetWords() {
        assertTrue(EmotionInterpreter.RESET_WORDS.containsMatchIn("원본으로 돌려줘"))
    }

    @Test
    fun unknownIsNull() {
        assertNull(interpretMood("오늘 점심 뭐 먹지"))
    }

    @Test
    fun mixedMoodsAreWeakened() {
        val r = mood("따뜻하고 몽환적인")
        assertEquals(listOf("dreamy", "warm"), r.moodIds)
        assertEquals("몽환적인 + 따뜻한", r.label)
        // warmth: 8*0.7 + 35*0.7 = 30.1 → 30
        assertEquals(30, r.deltas.getValue(Param.WARMTH))
    }

    @Test
    fun directOnlyIsAdjust() {
        val r = mood("밝게 해줘")
        assertEquals(MoodMode.ADJUST, r.mode)
        assertEquals(20, r.deltas.getValue(Param.BRIGHTNESS))
        assertEquals("밝기 올림", r.label)
    }

    @Test
    fun clampKeepsRange() {
        var p = FilterParams.NEUTRAL
        repeat(10) { p = applyMood(p, mood("밝게")) }
        assertEquals(100, p[Param.BRIGHTNESS])
        p = applyMood(p, mood("비네팅 빼줘"))
        assertEquals(0, p[Param.VIGNETTE])
    }

    @Test
    fun explainFormats() {
        val r = mood("몽환적인 느낌으로")
        val after = applyMood(FilterParams.NEUTRAL, r)
        assertEquals("몽환적인 느낌으로 페이드 +40, 대비 −20, 채도 −15, 색조 +15", explain(r, FilterParams.NEUTRAL, after))

        val more = mood("조금 더 따뜻하게", hasCurrent = true)
        assertTrue(explain(more, after, applyMood(after, more)).startsWith("따뜻한 쪽으로 조금 더: "))

        val less = mood("덜 따뜻하게", hasCurrent = true)
        assertTrue(explain(less, after, applyMood(after, less)).startsWith("따뜻한 느낌을 덜어서: "))

        val direct = mood("밝게")
        assertEquals("밝기 올림: 밝기 +20", explain(direct, FilterParams.NEUTRAL, applyMood(FilterParams.NEUTRAL, direct)))

        val mono = mood("흑백")
        val m = applyMood(FilterParams.NEUTRAL, mono)
        assertEquals("흑백: 더 바꿀 수 있는 범위를 넘었어요", explain(mono, m, m))
    }

    @Test
    fun nearestMoodFindsChip() {
        for (m in MOODS) {
            assertEquals(m.id, nearestMood(applyMood(FilterParams.NEUTRAL, EmotionInterpreter.moodChip(m))))
        }
        assertNull(nearestMood(FilterParams.NEUTRAL))
    }

    @Test
    fun nudgeMatchesSpokenEquivalent() {
        for (m in MOODS) {
            val more = mood("조금 더 ${m.label}", hasCurrent = true)
            val less = mood("조금 덜 ${m.label}", hasCurrent = true)
            if (more.moodIds != listOf(m.id)) continue // 다른 감성 단어와 겹치는 라벨은 건너뜀
            assertEquals(more, EmotionInterpreter.nudge(m, less = false))
            assertEquals(less, EmotionInterpreter.nudge(m, less = true))
        }
    }
}
