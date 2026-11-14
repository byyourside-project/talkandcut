package com.byyourside.talkandcut.filter

import com.byyourside.talkandcut.filter.Param.BRIGHTNESS
import com.byyourside.talkandcut.filter.Param.CONTRAST
import com.byyourside.talkandcut.filter.Param.FADE
import com.byyourside.talkandcut.filter.Param.GRAIN
import com.byyourside.talkandcut.filter.Param.SATURATION
import com.byyourside.talkandcut.filter.Param.TINT
import com.byyourside.talkandcut.filter.Param.VIGNETTE
import com.byyourside.talkandcut.filter.Param.WARMTH

// 웹 src/emotion.js의 MOODS 이식. 순서·정규식·벡터 그대로.

data class Mood(val id: String, val label: String, val words: Regex, val vec: Map<Param, Int>)

private fun mood(id: String, label: String, words: String, vararg vec: Pair<Param, Int>) =
    Mood(id, label, Regex(words), linkedMapOf(*vec))

val MOODS: List<Mood> = listOf(
    mood("dreamy", "몽환적인", "몽환|꿈\\s?같|꿈결|아련|신비", SATURATION to -15, FADE to 40, WARMTH to 8, TINT to 15, CONTRAST to -20, BRIGHTNESS to 10),
    mood("warm", "따뜻한", "따뜻|따듯|포근|아늑|훈훈", WARMTH to 35, SATURATION to 5, FADE to 10, BRIGHTNESS to 5),
    mood("cool", "차가운", "차갑|차가|시원|쿨한|서늘|냉정", WARMTH to -35, CONTRAST to 10, SATURATION to -5),
    mood("gloomy", "쓸쓸한", "우울|쓸쓸|외로|슬픈|슬프|울적|비\\s?오는", SATURATION to -40, WARMTH to -15, BRIGHTNESS to -10, CONTRAST to -5, FADE to 15),
    mood("happy", "신나는", "행복|신나|신난|발랄|기분\\s?좋|즐거|상큼|경쾌|활기", SATURATION to 25, BRIGHTNESS to 10, CONTRAST to 10, WARMTH to 10),
    mood("romantic", "설레는", "설레|설렘|사랑|로맨틱|러블리|달달|핑크", WARMTH to 15, TINT to 22, FADE to 15, BRIGHTNESS to 10, SATURATION to -5),
    mood("retro", "레트로", "레트로|빈티지|필름|옛날|추억|90년대|구십년대|아날로그|복고", FADE to 35, GRAIN to 40, WARMTH to 20, SATURATION to -20, VIGNETTE to 30, CONTRAST to -5),
    mood("chic", "시크한", "시크|도시|모던|세련|무심|힙한|힙하", SATURATION to -25, CONTRAST to 25, WARMTH to -10),
    mood("mono", "흑백", "흑백|모노|무채색", SATURATION to -100, CONTRAST to 15),
    mood("vivid", "선명한", "강렬|선명|쨍|화려|비비드|진하게", SATURATION to 35, CONTRAST to 30),
    mood("calm", "차분한", "차분|잔잔|평온|편안|고요|담백", SATURATION to -15, CONTRAST to -15, FADE to 15, WARMTH to 5),
    mood("fresh", "청량한", "청량|여름|바다|시원한\\s?여름|맑은|투명", WARMTH to -20, SATURATION to 20, BRIGHTNESS to 15, TINT to -5),
    mood("autumn", "가을", "가을|단풍|낙엽|노을|석양", WARMTH to 30, SATURATION to -5, TINT to 5, CONTRAST to 5, VIGNETTE to 15),
    mood("bright", "화사한", "화사|뽀샤시|뽀얗|환하", BRIGHTNESS to 25, FADE to 10, CONTRAST to -5),
    mood("moody", "분위기 있는", "분위기|무드|어두운\\s?느낌|감성적|감성", BRIGHTNESS to -15, VIGNETTE to 40, CONTRAST to 10, SATURATION to -10),
)

fun moodById(id: String?): Mood? = MOODS.firstOrNull { it.id == id }
