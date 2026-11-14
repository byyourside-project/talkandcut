package com.byyourside.talkandcut.filter

import kotlin.math.abs
import kotlin.math.sqrt

// 웹 src/emotion.js의 interpretMood, applyMood, explain, nearestMood 이식.
// 출력 스키마(MoodResult)는 intent.js FILTER 액션과 같다: { mode, moodIds, deltas, label, direction }

enum class MoodMode { SET, ADJUST }
enum class MoodDirection { MORE, LESS }

data class MoodResult(
    val mode: MoodMode,
    val moodIds: List<String>,
    val deltas: Map<Param, Int>,
    val label: String,
    val direction: MoodDirection? = null,
)

private class Direct(val key: Param, val up: Regex, val down: Regex)

object EmotionInterpreter {
    // 특정 파라미터를 직접 가리키는 표현 ("채도 올려", "밝게")
    private val DIRECT = listOf(
        Direct(Param.BRIGHTNESS, Regex("밝게|밝아|환하게|밝기\\s?(을|를)?\\s?(올|높)"), Regex("어둡게|어두워|밝기\\s?(을|를)?\\s?(내|낮|줄)")),
        Direct(Param.SATURATION, Regex("(채도|색감|색)\\s?(을|를|이)?\\s?(올|높|진하|더\\s?진)"), Regex("(채도|색감)\\s?(을|를|이)?\\s?(내|낮|빼|줄|연하)|색\\s?(을|를)?\\s?(빼|줄)")),
        Direct(Param.CONTRAST, Regex("대비\\s?(를|을)?\\s?(올|높|강하|세게)"), Regex("대비\\s?(를|을)?\\s?(내|낮|약하|줄)")),
        Direct(Param.GRAIN, Regex("(그레인|노이즈|입자)\\s?(을|를)?\\s?(넣|올|추가|더)"), Regex("(그레인|노이즈|입자)\\s?(을|를)?\\s?(빼|없|줄)")),
        Direct(Param.VIGNETTE, Regex("(비네팅|가장자리)\\s?(를|을)?\\s?(넣|어둡|올|더)"), Regex("(비네팅)\\s?(을|를)?\\s?(빼|없|줄)")),
    )

    private val RELATIVE = Regex("더|덜|조금\\s?만|좀\\s?만|아까보다|보다|약간\\s?더|한\\s?단계")
    private val LESS = Regex("덜|약하게|빼\\s?줘|줄여")
    private val SOFT = Regex("살짝|조금|약간|좀|은은|가볍게")
    private val STRONG = Regex("많이|아주|완전|엄청|진짜|훨씬|확|세게|강하게|제대로")

    val RESET_WORDS = Regex("초기화|원본|원래대로|필터\\s?(꺼|빼|없|해제)|보정\\s?(꺼|빼|없|해제)|자연스럽게|노\\s?필터")

    /** 발화에서 감성/보정 의도를 해석한다. 해당 없으면 null. */
    fun interpretMood(text: String, hasCurrent: Boolean = false): MoodResult? {
        val moods = MOODS.filter { it.words.containsMatchIn(text) }
        val directs = mutableListOf<Pair<Param, Int>>()
        for (d in DIRECT) {
            if (d.down.containsMatchIn(text)) directs += d.key to -1
            else if (d.up.containsMatchIn(text)) directs += d.key to 1
        }
        if (moods.isEmpty() && directs.isEmpty()) return null

        val less = LESS.containsMatchIn(text)
        val relative = hasCurrent && (RELATIVE.containsMatchIn(text) || less)
        val scale = if (STRONG.containsMatchIn(text)) 1.5 else if (SOFT.containsMatchIn(text)) 0.6 else 1.0

        val deltas = linkedMapOf<Param, Double>()
        fun add(k: Param, v: Double) { deltas[k] = (deltas[k] ?: 0.0) + v }

        // 여러 감성이 섞이면 각각 약하게 합성 ("따뜻하고 몽환적인")
        val mix = if (moods.size > 1) 0.7 else 1.0
        for (m in moods) {
            for ((k, v) in m.vec) {
                add(k, v * scale * mix * (if (relative) 0.7 else 1.0) * (if (less && relative) -1 else 1))
            }
        }
        for ((k, sign) in directs) add(k, 20.0 * sign * scale)

        // 감성 단어 없이 "밝게"만 말한 경우는 항상 현재 값 기준 조정
        val mode = if (relative || (moods.isEmpty() && directs.isNotEmpty())) MoodMode.ADJUST else MoodMode.SET
        val label = if (moods.isNotEmpty()) {
            moods.joinToString(" + ") { it.label }
        } else {
            directs.joinToString(", ") { (k, sign) -> "${k.label} ${if (sign > 0) "올림" else "내림"}" }
        }
        val direction = if (relative) (if (less) MoodDirection.LESS else MoodDirection.MORE) else null
        return MoodResult(mode, moods.map { it.id }, deltas.mapValues { it.value.jsRound() }, label, direction)
    }

    /** 감성 칩을 눌렀을 때: 웹 칩 클릭과 같은 set 결과. */
    fun moodChip(mood: Mood): MoodResult = MoodResult(MoodMode.SET, listOf(mood.id), mood.vec, mood.label)

    /**
     * 메뉴 "조금 더 / 조금 덜". interpretMood("조금 더 <감성>", hasCurrent=true)와 같은 값.
     * (SOFT 0.6 × relative 0.7, 덜이면 부호 반대)
     */
    fun nudge(mood: Mood, less: Boolean): MoodResult {
        val sign = if (less) -1 else 1
        val deltas = mood.vec.mapValues { (_, v) -> (v * 0.6 * 0.7 * sign).jsRound() }
        return MoodResult(MoodMode.ADJUST, listOf(mood.id), deltas, mood.label, if (less) MoodDirection.LESS else MoodDirection.MORE)
    }

    /** 해석 결과를 현재 파라미터에 적용 */
    fun applyMood(current: FilterParams, result: MoodResult): FilterParams {
        val base = if (result.mode == MoodMode.SET) FilterParams.NEUTRAL else current
        val next = Param.entries.associateWith { p -> (base[p] + (result.deltas[p] ?: 0)).toDouble() }
        return FilterParams.clamped(next)
    }

    /** 사용자에게 보여줄 "보정 이유" 문장 */
    fun explain(result: MoodResult, before: FilterParams, after: FilterParams): String {
        val changes = Param.entries
            .map { it to after[it] - before[it] }
            .filter { it.second != 0 }
            .sortedByDescending { abs(it.second) }
            .take(4)
            .map { (k, d) -> "${k.label} ${if (d > 0) "+" else "−"}${abs(d)}" }
        if (changes.isEmpty()) return "${result.label}: 더 바꿀 수 있는 범위를 넘었어요"
        val list = changes.joinToString(", ")
        if (result.moodIds.isEmpty()) return "${result.label}: $list"
        return when (result.direction) {
            MoodDirection.MORE -> "${result.label} 쪽으로 조금 더: $list"
            MoodDirection.LESS -> "${result.label} 느낌을 덜어서: $list"
            null -> "${result.label} 느낌으로 $list"
        }
    }

    /** 현재 파라미터에서 가장 가까운 감성 id (포즈/프레임 추천용) */
    fun nearestMood(params: FilterParams): String? {
        var best: String? = null
        var bestScore = 0.0
        for (m in MOODS) {
            var dot = 0.0
            var n1 = 0.0
            var n2 = 0.0
            for (k in Param.entries) {
                val a = params[k].toDouble()
                val b = (m.vec[k] ?: 0).toDouble()
                dot += a * b; n1 += a * a; n2 += b * b
            }
            if (n1 == 0.0 || n2 == 0.0) continue
            val cos = dot / sqrt(n1 * n2)
            if (cos > bestScore) { bestScore = cos; best = m.id }
        }
        return if (bestScore > 0.5) best else null
    }
}
