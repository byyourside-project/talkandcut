package com.byyourside.talkandcut.booth

import com.byyourside.talkandcut.filter.FilterParams
import com.byyourside.talkandcut.frame.LayoutId
import com.byyourside.talkandcut.pose.Pose

/**
 * 웹 src/main.js의 state 중 Step 1에 필요한 필드.
 * Shot 타입은 Android Bitmap이지만 테스트를 위해 제네릭으로 둔다.
 * 컷은 보정 전 원본이며 이미 거울 모드(좌우반전)로 저장된다. 웹은 합성 때 flip하지만
 * 앱은 Step 4 합성/보정에서 다시 뒤집지 않는다.
 */
data class BoothState<Shot>(
    val layout: LayoutId = LayoutId.GRID,
    val shots: List<Shot?> = List(CUTS) { null },
    val retake: Int? = null,
    val counting: Boolean = false,
    val countdown: Int? = null,
    val shutterOn: Boolean = false,
    val cameraOn: Boolean = false,
    val reply: String = "",
    /** 보정값. 미리보기에만 적용되고 컷은 원본으로 보관한다. */
    val params: FilterParams = FilterParams.NEUTRAL,
    val moodId: String? = null,
    /** 인원(1~4). 음성·메뉴로만 바꾼다(자동 감지 없음). */
    val headcount: Int = 1,
    /** 컷별 포즈 후보. 첫 번째가 추천 포즈. */
    val plan: List<List<Pose>> = emptyList(),
    /** 컷별로 후보 중 몇 번째를 쓰는지("다른 포즈"마다 +1). */
    val pick: List<Int> = List(CUTS) { 0 },
) {
    /** 다음에 찍을 컷 index, 다 찍었으면 -1. */
    val nextIndex: Int get() = shots.indexOfFirst { it == null }

    /** 지금 찍을(또는 다시 찍을) 컷 index. */
    val currentIndex: Int get() = retake ?: nextIndex

    /** 지금 찍을 컷의 포즈. 다 찍었으면 null. */
    val currentPose: Pose? get() = poseAt(currentIndex)

    fun poseAt(i: Int): Pose? {
        val list = plan.getOrNull(i) ?: return null
        if (list.isEmpty()) return null
        return list[pick[i] % list.size]
    }

    val shotCount: Int get() = shots.count { it != null }

    val busy: Boolean get() = counting || shutterOn

    companion object {
        const val CUTS = 4
    }
}
