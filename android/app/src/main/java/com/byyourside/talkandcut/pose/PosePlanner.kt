package com.byyourside.talkandcut.pose

// 웹 src/pose.js recommendPlan 이식.

object PosePlanner {
    private val ROLE_ORDER = listOf("opener", "cute", "fun", "finale")

    private fun score(pose: Pose, moodId: String?, role: String): Int =
        (if (role in pose.roles) 3 else 0) + (if (moodId != null && moodId in pose.moods) 2 else 0)

    /**
     * 같은 인원의 포즈 중 네 컷에 맞는 후보를 골라 각 컷에서 모두 선택할 수 있게 한다.
     * 각 컷 목록의 첫 번째가 추천 포즈이고, 네 컷의 첫 포즈는 서로 겹치지 않는다.
     */
    fun recommendPlan(poses: List<Pose>, moodId: String?): List<List<Pose>> {
        val used = mutableSetOf<String>()
        return ROLE_ORDER.map { role ->
            // JS Array.prototype.sort는 안정 정렬이고 Kotlin sortedByDescending도 안정 정렬이다.
            val ranked = poses.sortedByDescending { score(it, moodId, role) }
            val first = ranked.firstOrNull { it.id !in used } ?: ranked[0]
            used += first.id
            listOf(first) + ranked.filter { it !== first }
        }
    }
}
