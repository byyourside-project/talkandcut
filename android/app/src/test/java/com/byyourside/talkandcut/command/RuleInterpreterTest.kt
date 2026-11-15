package com.byyourside.talkandcut.command

import com.byyourside.talkandcut.booth.Command
import com.byyourside.talkandcut.filter.EmotionInterpreter
import com.byyourside.talkandcut.filter.FilterParams
import com.byyourside.talkandcut.filter.MoodMode
import com.byyourside.talkandcut.filter.Param
import com.byyourside.talkandcut.frame.LayoutId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 웹 tests/intent.test.mjs 이식 + 웹 parseUtterance 출력 JSON과 그대로 비교. */
class RuleInterpreterTest {
    private fun parse(t: String, hasFilter: Boolean = false) = RuleInterpreter.parse(t, hasFilter)
    private fun acts(t: String, hasFilter: Boolean = false) = parse(t, hasFilter).map(CommandJson::action)
    private fun filter(t: String, hasFilter: Boolean = false) = (parse(t, hasFilter)[0] as Command.Filter).result

    @Test
    fun captureAndDelay() {
        assertEquals(listOf(Command.Capture(3)), parse("찍어줘"))
        assertEquals(5, (parse("5초 뒤에 찍어")[0] as Command.Capture).delay)
        assertEquals(5, (parse("다섯 초 뒤에 찍어")[0] as Command.Capture).delay)
        assertEquals(0, (parse("바로 찍어")[0] as Command.Capture).delay)
        assertEquals(listOf("CAPTURE"), acts("치즈"))
    }

    @Test fun handlesSyllableSpacesFromOfflineAsr() {
        assertEquals(listOf(Command.Capture(0)), parse("바로 찍 어 줘"))
        assertEquals(listOf(Command.Capture(3)), parse("찍 어 줘"))
        assertEquals(listOf(Command.Capture(0)), parse("바 로 찍 어"))
        assertEquals(listOf(Command.Capture(3)), parse("직어줘"))
    }

    @Test
    fun poseMatchCapture() {
        assertEquals(listOf(Command.Capture(3, poseMatch = true)), parse("포즈 맞추면 찍어줘"))
    }

    @Test
    fun retakeAndCutNumber() {
        assertEquals(listOf(Command.Retake(1, 3)), parse("두 번째 컷 다시 찍어"))
        assertEquals(3, (parse("마지막 사진 다시 찍어줘")[0] as Command.Retake).index)
        assertEquals(null, (parse("다시 찍을래")[0] as Command.Retake).index)
    }

    @Test
    fun filterBeforeCapture() {
        assertEquals(listOf("FILTER", "CAPTURE"), acts("따뜻한 느낌으로 3초 뒤에 찍어줘"))
    }

    @Test
    fun setVersusAdjust() {
        val set = filter("몽환적인 느낌으로 해줘")
        assertEquals(MoodMode.SET, set.mode)
        assertEquals(listOf("dreamy"), set.moodIds)

        val adj = filter("조금 더 따뜻하게", hasFilter = true)
        assertEquals(MoodMode.ADJUST, adj.mode)
        assertTrue(adj.deltas.getValue(Param.WARMTH) in 1..34)

        assertTrue(filter("아까보다 덜 따뜻하게", hasFilter = true).deltas.getValue(Param.WARMTH) < 0)

        val dark = filter("좀 더 어둡게", hasFilter = true)
        assertEquals(MoodMode.ADJUST, dark.mode)
        assertTrue(dark.deltas.getValue(Param.BRIGHTNESS) < 0)
    }

    @Test
    fun multiTurn() {
        var p = EmotionInterpreter.applyMood(FilterParams.NEUTRAL, filter("따뜻하게"))
        val w1 = p[Param.WARMTH]
        p = EmotionInterpreter.applyMood(p, filter("조금 더 따뜻하게", hasFilter = true))
        assertTrue(p[Param.WARMTH] > w1)
        p = EmotionInterpreter.applyMood(p, filter("흑백으로"))
        assertEquals(-100, p[Param.SATURATION])
        assertEquals(0, p[Param.WARMTH])
    }

    @Test
    fun retroFilm() {
        assertEquals(listOf("retro"), filter("90년대 필름카메라처럼").moodIds)
    }

    @Test
    fun reset() {
        assertEquals(listOf("RESET_FILTER"), acts("원본으로 돌려줘"))
    }

    @Test
    fun poseHeadcountFrameCaptionSave() {
        assertEquals(listOf(Command.Headcount(3)), parse("우리 세 명이야"))
        assertEquals(listOf("HEADCOUNT", "POSE_PLAN"), acts("둘이 찍을 포즈 추천해줘"))
        assertEquals(listOf("POSE_NEXT"), acts("다른 포즈"))
        assertEquals(listOf("FRAME_RECOMMEND"), acts("프레임 추천해줘"))
        assertEquals(listOf("FRAME_NEXT"), acts("다른 프레임으로 바꿔"))
        assertEquals(listOf(Command.Layout(LayoutId.GRID)), parse("2×2로 바꿔"))
        assertEquals(listOf(Command.Caption("우리 졸업 축하해")), parse("문구는 우리 졸업 축하해로 해줘"))
        assertEquals(listOf("SAVE"), acts("저장해줘"))
        assertEquals(listOf("START"), acts("처음부터 다시 시작"))
    }

    @Test
    fun unknownIsEmpty() {
        assertEquals(emptyList<Command>(), parse("오늘 점심 뭐 먹지"))
    }

    @Test
    fun homeAndCancelAreAlone() {
        assertEquals(listOf("HOME"), acts("시작 화면으로 돌아가"))
        assertEquals(listOf("CANCEL"), acts("촬영 취소"))
        assertEquals(listOf("CAPTION"), acts("문구는 홈으로로 해줘"))
    }

    @Test
    fun vPoseThenCapture() {
        assertEquals(listOf(Command.PoseV), parse("브이 포즈"))
        assertEquals(listOf(Command.PoseV, Command.Capture(0)), parse("브이 포즈로 바로 찍어"))
    }

    /** 기대값은 웹 src/intent.js parseUtterance를 node로 돌린 JSON. */
    private val web = listOf(
        Triple("찍어줘", false, """[{"action":"CAPTURE","delay":3}]"""),
        Triple("5초 뒤에 찍어", false, """[{"action":"CAPTURE","delay":5}]"""),
        Triple("다섯 초 뒤에 찍어", false, """[{"action":"CAPTURE","delay":5}]"""),
        Triple("바로 찍어", false, """[{"action":"CAPTURE","delay":0}]"""),
        Triple("치즈", false, """[{"action":"CAPTURE","delay":3}]"""),
        Triple("포즈 맞추면 찍어줘", false, """[{"action":"CAPTURE","delay":3,"when":"POSE_MATCH"}]"""),
        Triple("두 번째 컷 다시 찍어", false, """[{"action":"RETAKE","index":1,"delay":3}]"""),
        Triple("마지막 사진 다시 찍어줘", false, """[{"action":"RETAKE","index":3,"delay":3}]"""),
        Triple("다시 찍을래", false, """[{"action":"RETAKE","index":null,"delay":3}]"""),
        Triple("따뜻한 느낌으로 3초 뒤에 찍어줘", false, """[{"action":"FILTER","mode":"set","moodIds":["warm"],"deltas":{"brightness":5,"fade":10,"saturation":5,"warmth":35},"label":"따뜻한","direction":null},{"action":"CAPTURE","delay":3}]"""),
        Triple("몽환적인 느낌으로 해줘", false, """[{"action":"FILTER","mode":"set","moodIds":["dreamy"],"deltas":{"brightness":10,"contrast":-20,"fade":40,"saturation":-15,"tint":15,"warmth":8},"label":"몽환적인","direction":null}]"""),
        Triple("조금 더 따뜻하게", true, """[{"action":"FILTER","mode":"adjust","moodIds":["warm"],"deltas":{"brightness":2,"fade":4,"saturation":2,"warmth":15},"label":"따뜻한","direction":"more"}]"""),
        Triple("아까보다 덜 따뜻하게", true, """[{"action":"FILTER","mode":"adjust","moodIds":["warm"],"deltas":{"brightness":-3,"fade":-7,"saturation":-3,"warmth":-24},"label":"따뜻한","direction":"less"}]"""),
        Triple("좀 더 어둡게", true, """[{"action":"FILTER","mode":"adjust","moodIds":[],"deltas":{"brightness":-12},"label":"밝기 내림","direction":"more"}]"""),
        Triple("따뜻하게", false, """[{"action":"FILTER","mode":"set","moodIds":["warm"],"deltas":{"brightness":5,"fade":10,"saturation":5,"warmth":35},"label":"따뜻한","direction":null}]"""),
        Triple("흑백으로", false, """[{"action":"FILTER","mode":"set","moodIds":["mono"],"deltas":{"contrast":15,"saturation":-100},"label":"흑백","direction":null}]"""),
        Triple("90년대 필름카메라처럼", false, """[{"action":"FILTER","mode":"set","moodIds":["retro"],"deltas":{"contrast":-5,"fade":35,"grain":40,"saturation":-20,"vignette":30,"warmth":20},"label":"레트로","direction":null}]"""),
        Triple("원본으로 돌려줘", false, """[{"action":"RESET_FILTER"}]"""),
        Triple("우리 세 명이야", false, """[{"action":"HEADCOUNT","count":3}]"""),
        Triple("둘이 찍을 포즈 추천해줘", false, """[{"action":"HEADCOUNT","count":2},{"action":"POSE_PLAN"}]"""),
        Triple("다른 포즈", false, """[{"action":"POSE_NEXT"}]"""),
        Triple("프레임 추천해줘", false, """[{"action":"FRAME_RECOMMEND"}]"""),
        Triple("다른 프레임으로 바꿔", false, """[{"action":"FRAME_NEXT"}]"""),
        Triple("2×2로 바꿔", false, """[{"action":"LAYOUT","layout":"grid"}]"""),
        Triple("문구는 우리 졸업 축하해로 해줘", false, """[{"action":"CAPTION","text":"우리 졸업 축하해"}]"""),
        Triple("저장해줘", false, """[{"action":"SAVE"}]"""),
        Triple("처음부터 다시 시작", false, """[{"action":"START"}]"""),
        Triple("오늘 점심 뭐 먹지", false, """[]"""),
        Triple("시작 화면으로 돌아가", false, """[{"action":"HOME"}]"""),
        Triple("촬영 취소", false, """[{"action":"CANCEL"}]"""),
        Triple("문구는 홈으로로 해줘", false, """[{"action":"CAPTION","text":"홈으로"}]"""),
        Triple("브이 포즈", false, """[{"action":"POSE_V"}]"""),
        Triple("브이 포즈로 바로 찍어", false, """[{"action":"POSE_V"},{"action":"CAPTURE","delay":0}]"""),
        Triple("몽환적인 느낌으로", false, """[{"action":"FILTER","mode":"set","moodIds":["dreamy"],"deltas":{"brightness":10,"contrast":-20,"fade":40,"saturation":-15,"tint":15,"warmth":8},"label":"몽환적인","direction":null}]"""),
        Triple("우리 두 명이야", false, """[{"action":"HEADCOUNT","count":2}]"""),
        Triple("1×4로 바꿔 찍어", false, """[{"action":"LAYOUT","layout":"strip"},{"action":"CAPTURE","delay":3}]"""),
        Triple("혼자 찍을래", false, """[{"action":"HEADCOUNT","count":1},{"action":"CAPTURE","delay":3}]"""),
        Triple("문구 지워줘", false, """[{"action":"CAPTION","text":""}]"""),
        Triple("10초 뒤에 찍어", false, """[{"action":"CAPTURE","delay":10}]"""),
        Triple("세로 한 줄로 바꿔", false, """[{"action":"LAYOUT","layout":"strip"}]"""),
    )

    @Test
    fun jsonMatchesWebParser() {
        for ((text, hasFilter, expected) in web) {
            val actual = JsonArray(parse(text, hasFilter).map(CommandJson::toJson))
            assertEquals(text, Json.parseToJsonElement(expected), actual)
        }
    }
}
