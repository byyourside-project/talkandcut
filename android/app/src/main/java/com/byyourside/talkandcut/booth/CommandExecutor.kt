package com.byyourside.talkandcut.booth

/** Command를 BoothViewModel 동작으로 바꾸고 응답 문구를 돌려준다. */
class CommandExecutor(private val booth: BoothController) {
    fun execute(command: Command): String {
        val text = when (command) {
            is Command.Capture -> booth.requestCapture(command.delay)
            Command.Cancel -> booth.cancelCapture()
            is Command.Retake -> booth.retake(command.index, command.delay)
            Command.Start -> booth.restart()
            is Command.Layout -> booth.setLayout(command.layout)
            is Command.Filter -> booth.applyFilter(command.result)
            Command.ResetFilter -> booth.resetFilter()
        }
        booth.reply(text)
        return text
    }
}

/** CommandExecutor가 쓰는 촬영 동작. BoothViewModel이 구현한다. */
interface BoothController {
    fun requestCapture(delay: Int = 3): String
    fun cancelCapture(): String
    fun retake(index: Int?, delay: Int = 3): String
    fun restart(): String
    fun setLayout(layout: com.byyourside.talkandcut.frame.LayoutId): String
    fun applyFilter(result: com.byyourside.talkandcut.filter.MoodResult): String
    fun resetFilter(): String
    fun reply(text: String)
}
