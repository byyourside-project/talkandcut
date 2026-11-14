package com.byyourside.talkandcut.command

import com.byyourside.talkandcut.booth.Command
import com.byyourside.talkandcut.frame.LayoutId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Command ↔ 웹 src/intent.js 상단 스키마 JSON.
 * 메뉴 "마지막 명령 JSON"에 쓰고, 모델을 붙일 때 모델 출력 JSON을 Command로 바꾸는 기준이 된다.
 */
object CommandJson {
    /** intent.js의 action 이름. 터치 전용 명령은 앱에서 붙인 이름. */
    fun action(c: Command): String = when (c) {
        is Command.Capture -> "CAPTURE"
        Command.Cancel -> "CANCEL"
        is Command.Retake -> "RETAKE"
        Command.Start -> "START"
        Command.Home -> "HOME"
        is Command.Filter -> "FILTER"
        Command.ResetFilter -> "RESET_FILTER"
        is Command.Headcount -> "HEADCOUNT"
        Command.PoseNext -> "POSE_NEXT"
        Command.PosePlan -> "POSE_PLAN"
        Command.PoseV -> "POSE_V"
        is Command.Layout -> "LAYOUT"
        Command.FrameRecommend -> "FRAME_RECOMMEND"
        Command.FrameNext -> "FRAME_NEXT"
        is Command.Caption -> "CAPTION"
        Command.Save -> "SAVE"
        is Command.Theme -> "THEME"
        is Command.Result -> "RESULT"
    }

    fun toJson(c: Command): JsonObject = buildJsonObject {
        put("action", action(c))
        when (c) {
            is Command.Capture -> {
                put("delay", c.delay)
                if (c.poseMatch) put("when", "POSE_MATCH")
            }
            is Command.Retake -> {
                put("index", c.index?.let { JsonPrimitive(it) } ?: JsonNull)
                put("delay", c.delay)
            }
            is Command.Filter -> {
                val r = c.result
                put("mode", r.mode.name.lowercase())
                put("moodIds", JsonArray(r.moodIds.map { JsonPrimitive(it) }))
                put("deltas", JsonObject(r.deltas.entries.associate { (k, v) -> k.name.lowercase() to JsonPrimitive(v) }))
                put("label", r.label)
                put("direction", r.direction?.name?.lowercase()?.let { JsonPrimitive(it) } ?: JsonNull)
            }
            is Command.Headcount -> put("count", c.count)
            is Command.Layout -> put("layout", if (c.layout == LayoutId.STRIP) "strip" else "grid")
            is Command.Caption -> put("text", c.text)
            is Command.Theme -> put("themeId", c.themeId)
            is Command.Result -> put("open", c.open)
            else -> {}
        }
    }
}
