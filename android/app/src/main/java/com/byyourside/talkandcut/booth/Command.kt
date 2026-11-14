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

    /** LAYOUT */
    data class Layout(val layout: LayoutId) : Command
}
