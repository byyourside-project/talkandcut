package com.byyourside.talkandcut.booth

import com.byyourside.talkandcut.filter.EmotionInterpreter
import com.byyourside.talkandcut.filter.FilterParams
import com.byyourside.talkandcut.filter.MoodMode
import com.byyourside.talkandcut.filter.MoodResult
import com.byyourside.talkandcut.frame.Frames
import com.byyourside.talkandcut.frame.LayoutId
import com.byyourside.talkandcut.frame.themeById
import com.byyourside.talkandcut.frame.Layouts
import com.byyourside.talkandcut.pose.PoseCatalog
import com.byyourside.talkandcut.pose.PosePlanner
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

/** 결과물 쪽 작업. 보정 적용 평균 색(프레임 추천 입력)과 갤러리 저장. */
interface FrameTools<Shot> {
    suspend fun averageColor(shots: List<Shot?>, params: FilterParams): DoubleArray?

    /** 지금 상태(레이아웃·테마·보정·문구)로 합성해서 저장. 성공하면 true. */
    suspend fun save(state: BoothState<Shot>): Boolean
}

private class NoFrameTools<Shot> : FrameTools<Shot> {
    override suspend fun averageColor(shots: List<Shot?>, params: FilterParams): DoubleArray? = null
    override suspend fun save(state: BoothState<Shot>) = false
}

/**
 * 웹 src/main.js 촬영 흐름(nextIndex, requestCapture, startCountdown, shoot, cancelCapture,
 * retake, resetSession)과 프레임(recommendNow, applyRec, FRAME_*, CAPTION, save) 이식.
 * Android 의존성이 없어 JUnit으로 바로 검증한다.
 */
class Booth<Shot>(
    private val scope: CoroutineScope,
    private val camera: CaptureSource<Shot>,
    private val poses: PoseCatalog,
    private val sounds: BoothSounds = BoothSounds.None,
    private val frames: FrameTools<Shot> = NoFrameTools(),
) : BoothController {

    private val _state = MutableStateFlow(BoothState<Shot>(plan = PosePlanner.recommendPlan(poses.poses(1), null)))
    val state: StateFlow<BoothState<Shot>> = _state.asStateFlow()

    private var countdownJob: Job? = null
    private var shutterJob: Job? = null

    /** 보정 적용 평균 색. 컷·보정이 바뀔 때마다 미리 계산해 둔다(웹은 추천할 때 계산). */
    private var avgRgb: DoubleArray? = null
    private var avgJob: Job? = null

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
            val done = s.nextIndex < 0
            if (done) {
                avgJob?.cancel()
                avgRgb = frames.averageColor(s.shots, s.params)
            } else {
                refreshAverage()
            }
            delay(520)
            shutterJob = null
            _state.update { it.copy(shutterOn = false) }
            val next = s.nextIndex
            if (next < 0) {
                // 웹: recommendNow(!replaced) + showResult. 다시 찍은 경우엔 고른 프레임을 유지한다.
                recommendNow(apply = !replaced)
                _state.update { it.copy(resultOpen = true) }
            }
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
            // 웹: 네 컷이 다 있으면 결과 화면으로 돌아간다
            _state.update { it.copy(retake = null, resultOpen = it.nextIndex < 0 || it.resultOpen) }
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
        // 결과 보기에서 다시 찍으면 촬영 화면으로 돌아간다
        _state.update { it.copy(retake = idx, resultOpen = false) }
        if (s.cameraOn) return requestCapture(delay)
        return "${idx + 1}번째 컷을 다시 찍을게요"
    }

    override fun restart(): String {
        resetSession()
        return "새로 시작할게요. 첫 번째는 ${s.currentPose?.name}"
    }

    fun resetSession() {
        shutterJob?.cancel()
        shutterJob = null
        avgJob?.cancel()
        avgJob = null
        avgRgb = null
        cancelCountdown()
        // 웹 resetSession: 보정 끄기 + 인원 1명으로 다시 추천, 프레임 white, 문구 비움, 결과 닫기
        _state.update {
            BoothState(layout = it.layout, cameraOn = it.cameraOn, plan = PosePlanner.recommendPlan(poses.poses(1), null))
        }
    }

    // ---------- 포즈 ----------

    override fun setHeadcount(count: Int): String {
        val n = count.coerceIn(1, 4)
        _state.update {
            it.copy(headcount = n, plan = PosePlanner.recommendPlan(poses.poses(n), it.moodId), pick = List(BoothState.CUTS) { 0 })
        }
        return "${n}명에 맞춰 포즈를 골랐어요"
    }

    override fun poseNext(): String {
        val i = s.currentIndex
        if (i < 0) return "네 컷을 다 찍었어요"
        _state.update { st -> st.copy(pick = st.pick.toMutableList().also { it[i]++ }) }
        return "그럼 ${s.currentPose?.name} 어때요"
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
        val prevMood = s.moodId
        _state.update { it.copy(params = after, moodId = moodId) }
        if (moodId != prevMood) {
            // 분위기가 바뀌면 아직 안 찍은 컷의 포즈를 분위기에 맞게 다시 고른다
            _state.update { st ->
                val fresh = PosePlanner.recommendPlan(poses.poses(st.headcount), moodId)
                val plan = fresh.mapIndexed { i, list -> if (st.shots[i] != null) st.plan.getOrElse(i) { list } else list }
                st.copy(plan = plan, pick = List(BoothState.CUTS) { 0 })
            }
        }
        refreshAverage()
        return EmotionInterpreter.explain(result, before, after)
    }

    override fun resetFilter(): String {
        _state.update { it.copy(params = FilterParams.NEUTRAL, moodId = null) }
        refreshAverage()
        return "보정을 껐어요"
    }

    private fun refreshAverage() {
        avgJob?.cancel()
        val shots = s.shots
        val params = s.params
        if (shots.all { it == null }) {
            avgRgb = null
            return
        }
        avgJob = scope.launch { avgRgb = frames.averageColor(shots, params) }
    }

    // ---------- 프레임 ----------

    private fun recommendNow(apply: Boolean) {
        val recs = Frames.recommendFrames(avgRgb, s.moodId)
        _state.update { it.copy(recs = recs, recIndex = 0, themeId = if (apply) recs[0].themeId else it.themeId) }
    }

    override fun frameRecommend(): String {
        recommendNow(apply = true)
        val r = s.recs[0]
        return "${themeById(r.themeId).name} 추천해요. ${r.reasons[0]}"
    }

    override fun frameNext(): String {
        if (s.recs.isEmpty()) recommendNow(apply = false)
        val i = (s.recIndex + 1) % minOf(s.recs.size, 5)
        val id = s.recs[i].themeId
        _state.update { it.copy(recIndex = i, themeId = id) }
        return "${themeById(id).name}로 바꿨어요"
    }

    override fun setTheme(themeId: String): String {
        _state.update { it.copy(themeId = themeById(themeId).id) }
        return ""
    }

    override fun setCaption(text: String): String {
        _state.update { it.copy(caption = text) }
        return if (text.isNotEmpty()) "문구를 “$text”로 넣었어요" else "문구를 지웠어요"
    }

    override fun save(): String {
        if (s.shots.all { it == null }) return "아직 찍은 사진이 없어요"
        val snapshot = s
        scope.launch {
            if (!frames.save(snapshot)) reply("사진을 저장하지 못했어요. 다시 시도해 주세요")
        }
        return if (snapshot.shots.all { it != null }) "사진을 저장했어요" else "찍은 컷까지만 저장했어요"
    }

    override fun showResult(open: Boolean): String {
        if (open && s.shots.all { it == null }) return "아직 찍은 사진이 없어요"
        if (open && s.busy) return ""
        _state.update { it.copy(resultOpen = open) }
        return ""
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
