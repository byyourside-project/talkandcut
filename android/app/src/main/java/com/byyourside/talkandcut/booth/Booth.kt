package com.byyourside.talkandcut.booth

import com.byyourside.talkandcut.filter.EmotionInterpreter
import com.byyourside.talkandcut.filter.FilterParams
import com.byyourside.talkandcut.filter.MoodMode
import com.byyourside.talkandcut.filter.MoodResult
import com.byyourside.talkandcut.frame.LayoutId
import com.byyourside.talkandcut.frame.Layouts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 카메라에서 한 컷을 가져온다. aspect(가로/세로) 비율로 가운데를 잘라 돌려준다. 실패하면 null. */
fun interface CaptureSource<Shot> {
    suspend fun capture(aspect: Double): Shot?
}

/** 카운트다운 비프, 셔터음. */
interface BoothSounds {
    fun beep()
    fun shutter()

    object None : BoothSounds {
        override fun beep() {}
        override fun shutter() {}
    }
}

/**
 * 웹 src/main.js 촬영 흐름(nextIndex, requestCapture, startCountdown, shoot, cancelCapture,
 * retake, resetSession) 이식. Android 의존성이 없어 JUnit으로 바로 검증한다.
 */
class Booth<Shot>(
    private val scope: CoroutineScope,
    private val camera: CaptureSource<Shot>,
    private val sounds: BoothSounds = BoothSounds.None,
) : BoothController {

    private val _state = MutableStateFlow(BoothState<Shot>())
    val state: StateFlow<BoothState<Shot>> = _state.asStateFlow()

    private var countdownJob: Job? = null
    private var shutterJob: Job? = null

    private val s get() = _state.value

    fun setCameraOn(on: Boolean) = _state.update { it.copy(cameraOn = on) }

    override fun reply(text: String) {
        if (text.isNotEmpty()) _state.update { it.copy(reply = text) }
    }

    // ---------- 촬영 ----------

    override fun requestCapture(delay: Int): String {
        if (s.shutterOn) return "사진을 찍는 중이에요"
        val idx = s.currentIndex
        if (idx < 0) return "네 컷을 다 찍었어요"
        if (!s.cameraOn) return "카메라 연결을 확인해 주세요"
        if (s.counting) return "이미 촬영 중이에요"
        startCountdown(delay, idx)
        return if (delay > 0) "${idx + 1}번째 컷, ${delay}초 뒤에 찍을게요" else "바로 찍을게요"
    }

    private fun startCountdown(sec: Int, idx: Int) {
        cancelCountdown()
        _state.update { it.copy(counting = true) }
        countdownJob = scope.launch {
            for (n in sec downTo 1) {
                _state.update { it.copy(countdown = n) }
                sounds.beep()
                delay(1000)
            }
            _state.update { it.copy(countdown = null) }
            shoot(idx)
        }
    }

    private fun cancelCountdown() {
        val job = countdownJob
        countdownJob = null
        _state.update { it.copy(counting = false, countdown = null) }
        job?.cancel()
    }

    private fun shoot(idx: Int) {
        countdownJob = null
        _state.update { it.copy(counting = false, countdown = null) }
        if (!s.cameraOn) {
            reply("카메라를 준비 중이에요. 잠시 후 다시 찍어 주세요")
            return
        }
        _state.update { it.copy(shutterOn = true) }
        sounds.shutter()
        shutterJob = scope.launch {
            val shot = camera.capture(Layouts.cellAspect(s.layout))
            if (shot == null) {
                _state.update { it.copy(shutterOn = false) }
                reply("카메라를 준비 중이에요. 잠시 후 다시 찍어 주세요")
                return@launch
            }
            val replaced = s.retake != null
            _state.update { st ->
                st.copy(shots = st.shots.toMutableList().also { it[idx] = shot }, retake = null)
            }
            delay(520)
            shutterJob = null
            _state.update { it.copy(shutterOn = false) }
            val next = s.nextIndex
            reply(
                when {
                    next >= 0 -> "좋아요. ${next + 1}번째 컷을 준비해 주세요."
                    replaced -> "다시 찍은 컷으로 바꿨어요."
                    else -> "네 컷을 다 찍었어요"
                }
            )
        }
    }

    override fun cancelCapture(): String {
        val retaking = s.retake != null
        cancelCountdown()
        if (retaking) {
            _state.update { it.copy(retake = null) }
            return "원래 컷으로 돌아왔어요."
        }
        return "촬영을 멈췄어요"
    }

    override fun retake(index: Int?, delay: Int): String {
        if (s.busy) return "이번 촬영이 끝난 뒤 다시 시도해 주세요"
        val idx = index ?: s.shots.indexOfLast { it != null }
        if (idx !in s.shots.indices || s.shots[idx] == null) {
            return if (index == null) requestCapture(delay) else "아직 찍지 않은 컷이에요"
        }
        cancelCountdown()
        _state.update { it.copy(retake = idx) }
        if (s.cameraOn) return requestCapture(delay)
        return "${idx + 1}번째 컷을 다시 찍을게요"
    }

    override fun restart(): String {
        resetSession()
        return "새로 시작할게요."
    }

    fun resetSession() {
        shutterJob?.cancel()
        shutterJob = null
        cancelCountdown()
        _state.update { BoothState(layout = it.layout, cameraOn = it.cameraOn) }
    }

    // ---------- 보정 ----------

    /** 웹 applyFilter: 적용 후 바뀐 값을 설명하는 문장을 돌려준다. */
    override fun applyFilter(result: MoodResult): String {
        val before = s.params
        val after = EmotionInterpreter.applyMood(before, result)
        val moodId = if (result.mode == MoodMode.SET) {
            result.moodIds.firstOrNull() ?: EmotionInterpreter.nearestMood(after)
        } else {
            s.moodId ?: EmotionInterpreter.nearestMood(after)
        }
        _state.update { it.copy(params = after, moodId = moodId) }
        return EmotionInterpreter.explain(result, before, after)
    }

    override fun resetFilter(): String {
        _state.update { it.copy(params = FilterParams.NEUTRAL, moodId = null) }
        return "보정을 껐어요"
    }

    // ---------- 레이아웃 ----------

    override fun setLayout(layout: LayoutId): String {
        val name = Layouts.LAYOUTS.getValue(layout).name
        if (layout == s.layout) return "이미 ${name}을 선택했어요"
        // 웹은 홈에서만 고를 수 있다. 앱은 홈이 없으니 첫 컷 찍기 전까지 바꿀 수 있다.
        if (s.shotCount > 0 || s.busy) return "네컷 모양은 첫 컷을 찍기 전에만 바꿀 수 있어요"
        _state.update { it.copy(layout = layout) }
        return "${name}으로 바꿨어요"
    }
}
