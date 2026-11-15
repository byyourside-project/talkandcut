package com.byyourside.talkandcut.command

import com.byyourside.talkandcut.booth.BoothState
import com.byyourside.talkandcut.booth.Command
import com.byyourside.talkandcut.filter.FilterParams
import com.byyourside.talkandcut.frame.LayoutId

/** 명령 판단 교체 지점(docs/mobile-port-plan.md 6장). 지금은 [RuleInterpreter], 나중에 모델. */
interface CommandInterpreter {
    suspend fun interpret(text: String, ctx: BoothContext): List<Command>
}

interface InterpreterFeedback {
    val explanation: String?
    val rawOutput: String
    val interpreterName: String
}

/** 해석에 필요한 지금 상태. 모델에도 같은 내용을 넘긴다. */
data class BoothContext(
    val params: FilterParams,
    val moodId: String?,
    val headcount: Int,
    val layout: LayoutId,
    val shotCount: Int,
    val cameraStatus: String = "ready",
    val previousReply: String = "",
    val poseId: String? = null,
    val themeId: String = "white",
) {
    val hasFilter: Boolean get() = !params.isNeutral

    companion object {
        fun of(s: BoothState<*>) = BoothContext(s.params, s.moodId, s.headcount, s.layout, s.shotCount,
            cameraStatus = when {
                s.shutterOn -> "capturing"
                s.counting -> "countdown"
                s.resultOpen || s.currentIndex < 0 -> "review"
                !s.cameraOn -> "preview"
                else -> "ready"
            }, previousReply = s.reply, poseId = s.currentPose?.id, themeId = s.themeId)
    }
}
