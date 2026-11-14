package com.byyourside.talkandcut.pose

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 웹 src/pose-catalog.js 항목. assets/pose-catalog.json(android/tools/convert-poses.mjs로 생성)에서 읽는다.
 * image: PNG 크기 [w, h], bounds: 윤곽선 범위 [왼쪽, 위, 오른쪽, 아래], faceY: 얼굴 중심 높이(0~1).
 */
@Serializable
data class Pose(
    val id: String,
    val name: String,
    val tip: String,
    val roles: List<String>,
    val moods: List<String>,
    val image: List<Int>,
    val bounds: List<Int>,
    val faceY: Double,
)

/** 인원(1~4)별 포즈 목록. */
class PoseCatalog(val byCount: Map<Int, List<Pose>>) {
    fun poses(count: Int): List<Pose> = byCount.getValue(count)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): PoseCatalog =
            PoseCatalog(json.decodeFromString<Map<String, List<Pose>>>(text).mapKeys { it.key.toInt() })

        /** 인원별 이미지 경로(assets 기준). */
        fun assetPath(count: Int, pose: Pose) = "poses/$count/${pose.id}.webp"
    }
}
