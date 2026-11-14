package com.byyourside.talkandcut.frame

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 웹 src/frames.js THEMES·recommendFrames 이식. */
enum class Pattern { NONE, FILM, DOTS, STRIPES, CHECK, SPARKLE }

/** moods: 이 프레임이 어울리는 감성 id (Moods.kt 기준) */
data class Theme(
    val id: String,
    val name: String,
    val bg: String,
    val ink: String,
    val pattern: Pattern,
    val moods: List<String>,
)

data class FrameRec(val themeId: String, val score: Double, val reasons: List<String>)

val THEMES: List<Theme> = listOf(
    Theme("white", "클래식 화이트", "#FFFFFF", "#1A1A1A", Pattern.NONE, listOf("bright", "calm", "fresh", "happy")),
    Theme("black", "클래식 블랙", "#141414", "#F5F5F5", Pattern.NONE, listOf("chic", "mono", "moody", "cool")),
    Theme("film", "필름 스트립", "#1C1A17", "#E9C46A", Pattern.FILM, listOf("retro", "moody", "autumn", "mono")),
    Theme("peach", "복숭아 우유", "#FFD9CF", "#7A3B2E", Pattern.DOTS, listOf("romantic", "warm", "happy", "bright")),
    Theme("lavender", "라벤더 안개", "#D9D2F3", "#3F3668", Pattern.SPARKLE, listOf("dreamy", "romantic", "calm")),
    Theme("sky", "여름 하늘", "#BFE3F7", "#14456B", Pattern.STRIPES, listOf("fresh", "cool", "happy")),
    Theme("lemon", "레몬 소다", "#FFF06A", "#2B2B12", Pattern.CHECK, listOf("happy", "vivid", "bright")),
    Theme("forest", "숲속 오후", "#3E5641", "#F1E9D2", Pattern.NONE, listOf("autumn", "calm", "warm", "gloomy")),
    Theme("brick", "벽돌 레트로", "#B5523B", "#FFF3E4", Pattern.STRIPES, listOf("retro", "autumn", "warm", "vivid")),
    Theme("midnight", "한밤 블루", "#1F2A5A", "#DDE4FF", Pattern.SPARKLE, listOf("gloomy", "cool", "dreamy", "moody")),
)

fun themeById(id: String?): Theme = THEMES.firstOrNull { it.id == id } ?: THEMES[0]

object Frames {
    fun hexToRgb(hex: String): DoubleArray {
        val n = hex.substring(1).toInt(16)
        return doubleArrayOf(((n shr 16) and 255) / 255.0, ((n shr 8) and 255) / 255.0, (n and 255) / 255.0)
    }

    /** [h(0~360), s, l] */
    fun rgbToHsl(rgb: DoubleArray): DoubleArray {
        val (r, g, b) = Triple(rgb[0], rgb[1], rgb[2])
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) / 2
        if (mx == mn) return doubleArrayOf(0.0, 0.0, l)
        val d = mx - mn
        val s = if (l > 0.5) d / (2 - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> (g - b) / d + (if (g < b) 6 else 0)
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        }
        return doubleArrayOf(h * 60, s, l)
    }

    fun hueName(h: Double): String = when {
        h < 20 || h >= 340 -> "붉은"
        h < 50 -> "주황빛"
        h < 70 -> "노란"
        h < 160 -> "초록빛"
        h < 250 -> "푸른"
        h < 300 -> "보랏빛"
        else -> "분홍빛"
    }

    /** 프레임 추천. 점수 내림차순(같으면 THEMES 순서, JS 안정 정렬과 같음). */
    fun recommendFrames(avgRgb: DoubleArray?, moodId: String?): List<FrameRec> {
        val photo = avgRgb?.let { rgbToHsl(it) }
        return THEMES.map { t ->
            var score = 0.0
            val reasons = mutableListOf<String>()
            val (fh, fs, fl) = rgbToHsl(hexToRgb(t.bg)).let { Triple(it[0], it[1], it[2]) }

            if (moodId != null && moodId in t.moods) {
                score += 3
                reasons += "지금 보정한 분위기와 같은 계열"
            }
            if (photo != null) {
                val (ph, ps, pl) = Triple(photo[0], photo[1], photo[2])
                if (fs < 0.12) {
                    score += 1
                } else if (ps > 0.06) {
                    val dh = min(abs(fh - ph), 360 - abs(fh - ph))
                    if (dh < 40) { score += 2; reasons += "사진의 ${hueName(ph)} 톤과 같은 색 계열" }
                    else if (dh > 150) { score += 1; reasons += "${hueName(ph)} 사진과 대비돼서 또렷해요" }
                }
                if (pl > 0.55 && fl < 0.35) { score += 1.5; reasons += "밝은 사진이 어두운 바탕에서 돋보여요" }
                if (pl < 0.4 && fl > 0.7) { score += 1.5; reasons += "어두운 사진을 밝은 바탕이 받쳐줘요" }
                if (ps < 0.08 && fs < 0.12) { score += 1; reasons += "흑백·저채도 사진엔 무채색 프레임" }
            }
            if (reasons.isEmpty()) reasons += "무난하게 어울리는 기본 프레임"
            FrameRec(t.id, score, reasons.take(2))
        }.sortedByDescending { it.score }
    }
}
