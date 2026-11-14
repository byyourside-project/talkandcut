package com.byyourside.talkandcut.filter

import kotlin.math.floor

// 웹 src/emotion.js의 PARAM_META, NEUTRAL 이식.

enum class Param(val label: String, val min: Int, val max: Int) {
    BRIGHTNESS("밝기", -100, 100),
    CONTRAST("대비", -100, 100),
    SATURATION("채도", -100, 100),
    WARMTH("색온도", -100, 100),
    TINT("색조", -100, 100),
    FADE("페이드", 0, 100),
    VIGNETTE("비네팅", 0, 100),
    GRAIN("그레인", 0, 100),
}

/** -100..100(페이드·비네팅·그레인은 0..100) 범위 보정값. */
data class FilterParams(val values: Map<Param, Int> = emptyMap()) {
    operator fun get(p: Param): Int = values[p] ?: 0

    val isNeutral: Boolean get() = Param.entries.all { get(it) == 0 }

    companion object {
        val NEUTRAL = FilterParams()

        /** 웹 clampParams: 범위로 자르고 반올림. */
        fun clamped(raw: Map<Param, Double>): FilterParams =
            FilterParams(Param.entries.associateWith { p -> (raw[p] ?: 0.0).coerceIn(p.min.toDouble(), p.max.toDouble()).jsRound() })
    }
}

/** JS Math.round: .5는 +∞ 방향. */
internal fun Double.jsRound(): Int = floor(this + 0.5).toInt()
