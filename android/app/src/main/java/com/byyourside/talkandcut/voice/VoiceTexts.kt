package com.byyourside.talkandcut.voice

/**
 * 웹 main.js voiceLabels / voiceMessages / voiceErrors / updateVoicePrompt.
 * 문구는 웹 그대로이고 "브라우저"만 "기기"로 바꿨다(Android에 맞춤).
 */
object VoiceTexts {
    fun label(s: VoiceState): String = when (s) {
        VoiceState.OFF -> "마이크 꺼짐"
        VoiceState.CONNECTING -> "마이크 연결 중"
        VoiceState.LISTENING -> "듣는 중"
        VoiceState.SPEAKING -> "음성 안내 중"
        VoiceState.DENIED -> "마이크 권한 필요"
        VoiceState.UNSUPPORTED -> "음성 인식 미지원"
        VoiceState.ERROR -> "음성 인식 연결 실패"
    }

    private val errors = mapOf(
        "model-loading" to "한국어 음성 모델을 준비하고 있어요. 처음 실행할 때 잠시 걸릴 수 있어요.",
        "model-failed" to "음성 모델을 실행하지 못했어요. 메뉴에서 마이크를 다시 연결해 주세요.",
        "microphone-open-failed" to "USB 마이크를 열지 못했어요. 다른 녹음 앱을 종료한 뒤 마이크를 다시 연결해 주세요.",
        "timeout" to "기기가 음성 인식 시작에 응답하지 않았어요. 마이크 권한을 확인해 주세요.",
        "start-ended" to "기기가 음성 인식을 시작하지 못했어요. 메뉴에서 다시 연결해 주세요.",
        "audio-capture" to "마이크 입력을 열 수 없어요. 시스템의 입력 장치와 음소거 상태를 확인해 주세요.",
        "network" to "음성 인식 서비스에 연결할 수 없어요. 인터넷 연결을 확인해 주세요.",
        "service-not-allowed" to "기기에서 음성 인식 서비스를 사용할 수 없어요.",
        "language-not-supported" to "이 기기에서 한국어 음성 인식을 사용할 수 없어요.",
    )

    /** 상태 안내(오류일 때 원인). 정상이면 빈 문자열. */
    fun status(s: VoiceState, reason: String?): String = errors[reason] ?: when (s) {
        VoiceState.OFF -> "메뉴에서 마이크를 다시 연결할 수 있어요."
        VoiceState.DENIED -> "마이크 권한을 허용한 뒤 메뉴에서 다시 연결해 주세요."
        VoiceState.UNSUPPORTED -> "이 기기에서는 음성 인식을 지원하지 않아요."
        VoiceState.ERROR -> "기기의 음성 인식 설정을 확인하고 메뉴에서 다시 연결해 주세요."
        else -> ""
    }

    fun prompt(s: VoiceState, reason: String?): String = when {
        s == VoiceState.CONNECTING && reason == "model-loading" -> "인터넷 없이 한국어 음성을 인식해요"
        s == VoiceState.CONNECTING -> "권한 요청이 뜨면 마이크를 허용해 주세요"
        s == VoiceState.SPEAKING -> "안내가 끝나면 다시 들을게요"
        status(s, reason).isNotEmpty() -> "오른쪽 메뉴에서 터치로 조작할 수 있어요"
        else -> "“찍어줘”라고 말해 보세요"
    }
}
