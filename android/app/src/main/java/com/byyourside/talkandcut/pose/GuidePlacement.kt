package com.byyourside.talkandcut.pose

/**
 * 뷰파인더 안 윤곽선 배치(웹 CSS .pose-image img와 같은 의미, 모두 %).
 * heightPct: 이미지 높이 / 뷰파인더 높이, topPct: 이미지 위쪽 / 뷰파인더 높이,
 * shiftPct: 이미지 가로 중 이 지점(이미지 너비 대비 %)이 뷰파인더 가로 중앙에 온다.
 */
data class GuidePlacement(val heightPct: Double, val shiftPct: Double, val topPct: Double)

/** 웹 src/pose.js guidePlacement 이식. 얼굴 중심을 위쪽 1/3 근처에 맞추고, 위로 든 손과 아래쪽 윤곽에도 여백을 남긴다. */
fun guidePlacement(pose: Pose, aspect: Double): GuidePlacement {
    val (width, height) = pose.image.map { it.toDouble() }
    val (left, top, right, bottom) = pose.bounds.map { it.toDouble() }
    val faceY = pose.faceY
    val edge = 0.06
    val faceTarget = 0.36
    val scale = minOf(
        minOf(1.0, (1 - 2 * edge) * aspect * height / (right - left)),
        minOf((1 - 2 * edge) * height / (bottom - top), (1 - edge - faceTarget) / (bottom / height - faceY)),
        (faceTarget - edge) / (faceY - top / height),
    )
    return GuidePlacement(
        heightPct = scale * 100,
        shiftPct = (left + right) / (2 * width) * 100,
        topPct = (faceTarget - faceY * scale) * 100,
    )
}
