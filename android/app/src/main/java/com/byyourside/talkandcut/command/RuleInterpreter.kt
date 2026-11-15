package com.byyourside.talkandcut.command

import com.byyourside.talkandcut.booth.Command
import com.byyourside.talkandcut.filter.EmotionInterpreter
import com.byyourside.talkandcut.frame.LayoutId

/**
 * 웹 src/intent.js parseUtterance 이식. 발화 → 명령 목록.
 * 한 발화에 여러 명령이 들어갈 수 있다: "따뜻한 느낌으로 3초 뒤에 찍어줘" → [FILTER, CAPTURE]
 * 정규식은 웹과 같다.
 */
object RuleInterpreter : CommandInterpreter {
    private val KOR_NUM = mapOf(
        "한" to 1, "하나" to 1, "두" to 2, "둘" to 2, "세" to 3, "셋" to 3, "석" to 3,
        "네" to 4, "넷" to 4, "다섯" to 5, "여섯" to 6, "일곱" to 7, "열" to 10,
    )
    private val ORDINAL = mapOf(
        "첫" to 0, "1" to 0, "한" to 0, "두" to 1, "2" to 1, "세" to 2, "3" to 2, "네" to 3, "4" to 3, "마지막" to 3,
    )

    private fun toNumber(tok: String?): Int? {
        if (tok == null) return null
        if (Regex("^\\d+$").matches(tok)) return tok.toInt()
        return KOR_NUM[tok]
    }

    fun normalize(text: String): String =
        text.replace(Regex("[찍직]\\s*어\\s*줘"), "찍어줘")
            .replace(Regex("찍\\s+어"), "찍어")
            .replace(Regex("바\\s+로"), "바로")
            .replace(Regex("[.,!?~]"), " ").replace(Regex("\\s+"), " ").trim()

    private val CAPTURE = Regex("찍어|찍자|찍을래|찍어줘|찍어 줘|촬영|찰칵|치즈|김치|셔터|찍기")
    private val RETAKE = Regex("다시\\s?찍|재촬영|다시\\s?촬영|다시 해|다시할래")

    private fun parseDelay(t: String): Int {
        if (Regex("바로|지금|즉시|당장").containsMatchIn(t)) return 0
        val m = Regex("(\\d+|한|두|세|석|네|다섯|여섯|일곱|열)\\s?초").find(t)
        if (m != null) return minOf(10, toNumber(m.groupValues[1]) ?: 3)
        return 3
    }

    private fun parseOrdinal(t: String): Int? {
        val m = Regex("(첫|마지막|[1-4]|한|두|세|네)\\s?(번째|번|째)?\\s?(컷|사진|장)").find(t) ?: return null
        return ORDINAL[m.groupValues[1]]
    }

    override suspend fun interpret(text: String, ctx: BoothContext): List<Command> = parse(text, ctx.hasFilter)

    fun parse(raw: String, hasFilter: Boolean = false): List<Command> {
        val t = normalize(raw)
        if (t.isEmpty()) return emptyList()
        val actions = mutableListOf<Command>()

        // 문구 지정은 내용에 다른 키워드가 섞일 수 있으니 가장 먼저, 단독으로 처리
        val cap = Regex("(문구|글씨|텍스트|캡션)\\s?(는|은|를|을)?\\s?[\"'“]?(.+?)[\"'”]?\\s?(으로|로|라고)\\s?(해|바꿔|넣어|써|적어)").find(t)
        if (cap != null) return listOf(Command.Caption(cap.groupValues[3].trim()))
        if (Regex("(문구|글씨|캡션)\\s?(지워|빼|없애)").containsMatchIn(t)) return listOf(Command.Caption(""))

        if (Regex("(?:^홈$|홈으로|첫\\s?화면으로|시작\\s?화면으로)").containsMatchIn(t)) return listOf(Command.Home)
        if (Regex("취소|그만|멈춰|스톱|중지").containsMatchIn(t)) return listOf(Command.Cancel)

        if (Regex("(처음부터|새로)\\s?(다시|시작|찍)|다시\\s?시작|^시작|시작하자|시작해").containsMatchIn(t)) {
            actions += Command.Start
        }

        // 인원수
        val hc = Regex("(혼자|둘이|셋이|넷이|(\\d|한|두|세|네|다섯)\\s?명)").find(t)
        if (hc != null) {
            val map = mapOf("혼자" to 1, "둘이" to 2, "셋이" to 3, "넷이" to 4)
            val count = map[hc.groupValues[1]] ?: toNumber(hc.groups[2]?.value)
            if (count != null && count != 0) actions += Command.Headcount(minOf(4, count))
        }

        // 보정
        if (EmotionInterpreter.RESET_WORDS.containsMatchIn(t)) {
            actions += Command.ResetFilter
        } else {
            EmotionInterpreter.interpretMood(t, hasCurrent = hasFilter)?.let { actions += Command.Filter(it) }
        }

        // 포즈
        if (Regex("^(?:브이|[vV])$|(?:브이|[vV])\\s*(?:포즈|자세|하고|로\\s*찍)").containsMatchIn(t)) {
            actions += Command.PoseV
        } else if (t.contains("포즈")) {
            if (Regex("다른|바꿔|다음|별로|또").containsMatchIn(t)) actions += Command.PoseNext
            else if (Regex("추천|알려|뭐|어떻게|골라").containsMatchIn(t)) actions += Command.PosePlan
        }

        // 프레임
        if (Regex("프레임|테두리").containsMatchIn(t)) {
            if (Regex("다른|바꿔|다음|별로").containsMatchIn(t)) actions += Command.FrameNext
            else actions += Command.FrameRecommend
        }
        if (Regex("1\\s*[xX×]\\s*4|세로\\s?(로|형|네컷|한\\s?줄)|한\\s?줄|스트립").containsMatchIn(t)) {
            actions += Command.Layout(LayoutId.STRIP)
        } else if (Regex("(2|두)\\s?([xX×]|바이|곱하기)\\s?(2|두)|격자|정사각|네모\\s?칸|큰\\s?사진").containsMatchIn(t)) {
            actions += Command.Layout(LayoutId.GRID)
        }

        if (Regex("저장|다운로드|다운|뽑아|인화|출력").containsMatchIn(t)) actions += Command.Save

        // 촬영 (마지막에: 보정 먼저 반영하고 찍는다)
        if (RETAKE.containsMatchIn(t)) {
            actions += Command.Retake(parseOrdinal(t), parseDelay(t))
        } else if (CAPTURE.containsMatchIn(t) && !Regex("찍은|찍었").containsMatchIn(t)) {
            val poseMatch = Regex("포즈\\s?(맞추|맞으|맞춰|되)|(맞추|맞으)면|자세\\s?(잡|되)").containsMatchIn(t)
            // 포즈 단어 때문에 붙은 POSE_PLAN은 제거
            if (poseMatch) actions.remove(Command.PosePlan)
            actions += Command.Capture(parseDelay(t), poseMatch)
        }
        return actions
    }
}
