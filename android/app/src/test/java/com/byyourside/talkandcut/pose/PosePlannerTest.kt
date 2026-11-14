package com.byyourside.talkandcut.pose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosePlannerTest {
    private val roles = listOf("opener", "cute", "fun", "finale")

    private fun score(p: Pose, mood: String?, role: String) =
        (if (role in p.roles) 3 else 0) + (if (mood != null && mood in p.moods) 2 else 0)

    @Test
    fun firstPickIsBestUnusedForEachRole() {
        for (count in 1..4) {
            val poses = TestPoses.catalog.poses(count)
            for (mood in listOf(null, "romantic", "chic", "happy", "calm")) {
                val plan = PosePlanner.recommendPlan(poses, mood)
                val used = mutableSetOf<String>()
                plan.forEachIndexed { i, list ->
                    val first = list.first()
                    val best = poses.filter { it.id !in used }.maxOf { score(it, mood, roles[i]) }
                    assertEquals("$count $mood ${roles[i]}", best, score(first, mood, roles[i]))
                    used += first.id
                    // 나머지는 점수 내림차순
                    val rest = list.drop(1).map { score(it, mood, roles[i]) }
                    assertEquals(rest.sortedDescending(), rest)
                }
            }
        }
    }

    @Test
    fun deterministicAndMoodSensitive() {
        val poses = TestPoses.catalog.poses(1)
        assertEquals(PosePlanner.recommendPlan(poses, "chic"), PosePlanner.recommendPlan(poses, "chic"))
        // 시크 분위기에서 opener는 시크 포즈(팔짱/턱 괴기 등)
        assertTrue("chic" in PosePlanner.recommendPlan(poses, "chic")[0].first().moods)
    }
}
