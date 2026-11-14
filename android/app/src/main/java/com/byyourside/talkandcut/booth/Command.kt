package com.byyourside.talkandcut.booth

import com.byyourside.talkandcut.filter.MoodResult
import com.byyourside.talkandcut.frame.LayoutId

/**
 * 웹 src/intent.js 상단 스키마의 명령. Step 1에서는 촬영·레이아웃 명령만 둔다.
 * 메뉴 버튼과 (Step 5의) 음성 모두 이 객체를 만들어 CommandExecutor로 보낸다.
 */
sealed interface Command {
    /** CAPTURE. delay=0이면 바로 촬영. */
    data class Capture(val delay: Int = 3) : Command

    /** CANCEL */
    data object Cancel : Command

    /** RETAKE. index가 null이면 마지막으로 찍은 컷. */
    data class Retake(val index: Int? = null, val delay: Int = 3) : Command

    /** START: 처음부터 */
    data object Start : Command

    /** FILTER */
    data class Filter(val result: MoodResult) : Command

    /** RESET_FILTER */
    data object ResetFilter : Command

    /** HEADCOUNT */
    data class Headcount(val count: Int) : Command

    /** POSE_NEXT: 지금 컷의 다른 포즈 */
    data object PoseNext : Command

    /** LAYOUT */
    data class Layout(val layout: LayoutId) : Command

    /** FRAME_RECOMMEND: 사진·보정에 맞는 프레임 추천 후 적용 */
    data object FrameRecommend : Command

    /** FRAME_NEXT: 다음 추천 프레임 */
    data object FrameNext : Command

    /** 프레임 견본을 직접 누름(터치 전용, 응답 없음). */
    data class Theme(val themeId: String) : Command

    /** CAPTION. quiet이면 입력 중 실시간 반영이라 응답하지 않는다(웹 input 이벤트). */
    data class Caption(val text: String, val quiet: Boolean = false) : Command

    /** SAVE */
    data object Save : Command

    /** 결과 보기 열기/닫기(터치 전용). */
    data class Result(val open: Boolean) : Command
}
