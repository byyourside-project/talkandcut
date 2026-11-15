package com.byyourside.talkandcut.voice

/** SenseVoice may return language/emotion tags; they are not part of the user's command. */
object KoreanTranscript {
    fun clean(text: String): String = text.trim().replace(Regex("<\\|[^|]*\\|>"), "")
        .replace(Regex("[.!?。！？]+$"), "").trim()
}
