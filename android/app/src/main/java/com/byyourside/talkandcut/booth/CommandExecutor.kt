package com.byyourside.talkandcut.booth

import com.byyourside.talkandcut.filter.MoodResult
import com.byyourside.talkandcut.frame.LayoutId

/** Command를 Booth 동작으로 바꾸고 응답 문구를 돌려준다. */
class CommandExecutor(private val booth: BoothController) {
    fun execute(command: Command): String {
        val text = when (command) {
            is Command.Capture -> booth.requestCapture(command.delay, command.poseMatch)
            Command.Cancel -> booth.cancelCapture()
            is Command.Retake -> booth.retake(command.index, command.delay)
            Command.Start -> booth.restart()
            is Command.Layout -> booth.setLayout(command.layout)
            is Command.Filter -> booth.applyFilter(command.result)
            Command.ResetFilter -> booth.resetFilter()
            is Command.Headcount -> booth.setHeadcount(command.count)
            Command.PoseNext -> booth.poseNext()
            Command.PosePlan -> booth.posePlan()
            Command.PoseV -> booth.poseV()
            Command.Home -> booth.restart()
            Command.FrameRecommend -> booth.frameRecommend()
            Command.FrameNext -> booth.frameNext()
            is Command.Theme -> booth.setTheme(command.themeId)
            is Command.Caption -> booth.setCaption(command.text).takeUnless { command.quiet }.orEmpty()
            Command.Save -> booth.save()
            is Command.Result -> booth.showResult(command.open)
        }
        booth.reply(text)
        return text
    }
}

/** CommandExecutor가 쓰는 촬영 동작. [Booth]가 구현한다. 빈 문자열이면 응답하지 않는다. */
interface BoothController {
    fun requestCapture(delay: Int = 3, poseMatch: Boolean = false): String
    fun cancelCapture(): String
    fun retake(index: Int?, delay: Int = 3): String
    fun restart(): String
    fun setLayout(layout: LayoutId): String
    fun applyFilter(result: MoodResult): String
    fun resetFilter(): String
    fun setHeadcount(count: Int): String
    fun poseNext(): String
    fun posePlan(): String
    fun poseV(): String
    fun frameRecommend(): String
    fun frameNext(): String
    fun setTheme(themeId: String): String
    fun setCaption(text: String): String
    fun save(): String
    fun showResult(open: Boolean): String
    fun reply(text: String)
}
