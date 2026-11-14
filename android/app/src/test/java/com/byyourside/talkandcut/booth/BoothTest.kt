package com.byyourside.talkandcut.booth

import com.byyourside.talkandcut.filter.EmotionInterpreter
import com.byyourside.talkandcut.filter.Param
import com.byyourside.talkandcut.filter.moodById
import com.byyourside.talkandcut.frame.LayoutId
import com.byyourside.talkandcut.pose.PosePlanner
import com.byyourside.talkandcut.pose.TestPoses
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BoothTest {
    private var counter = 0

    private fun TestScope.booth(): Booth<String> =
        Booth<String>(this, { "shot${counter++}" }, TestPoses.catalog).also { it.setCameraOn(true) }

    @Test
    fun fourShotsInOrderThenDone() = runTest {
        val b = booth()
        for (i in 0 until 4) {
            assertEquals(i, b.state.value.nextIndex)
            assertEquals("${i + 1}번째 컷, 3초 뒤에 찍을게요", b.requestCapture(3))
            advanceUntilIdle()
        }
        assertEquals(-1, b.state.value.nextIndex)
        assertEquals("네 컷을 다 찍었어요", b.requestCapture(3))
        assertEquals("네 컷을 다 찍었어요", b.state.value.reply)
    }

    @Test
    fun immediateCaptureAndNextCutReply() = runTest {
        val b = booth()
        assertEquals("바로 찍을게요", b.requestCapture(0))
        advanceUntilIdle()
        assertEquals("shot0", b.state.value.shots[0])
        assertEquals("좋아요. 2번째 컷을 준비해 주세요.", b.state.value.reply)
        assertFalse(b.state.value.shutterOn)
    }

    @Test
    fun cameraOffIsRejected() = runTest {
        val b = booth()
        b.setCameraOn(false)
        assertEquals("카메라 연결을 확인해 주세요", b.requestCapture(3))
    }

    @Test
    fun secondRequestDuringCountdownAndCancel() = runTest {
        val b = booth()
        b.requestCapture(3)
        testScheduler.runCurrent()
        assertTrue(b.state.value.counting)
        assertEquals(3, b.state.value.countdown)
        assertEquals("이미 촬영 중이에요", b.requestCapture(3))
        assertEquals("촬영을 멈췄어요", b.cancelCapture())
        assertFalse(b.state.value.counting)
        assertNull(b.state.value.countdown)
        advanceUntilIdle()
        assertNull(b.state.value.shots[0])
    }

    @Test
    fun retakeReplacesOnlyThatCut() = runTest {
        val b = booth()
        repeat(3) { b.requestCapture(0); advanceUntilIdle() }
        assertEquals("2번째 컷, 3초 뒤에 찍을게요", b.retake(1, 3))
        assertEquals(1, b.state.value.retake)
        advanceUntilIdle()
        val shots = b.state.value.shots
        assertEquals(listOf("shot0", "shot3", "shot2", null), shots)
        assertNull(b.state.value.retake)
        assertEquals(3, b.state.value.nextIndex)
    }

    @Test
    fun retakeUnshotCutIsRejected() = runTest {
        val b = booth()
        b.requestCapture(0); advanceUntilIdle()
        assertEquals("아직 찍지 않은 컷이에요", b.retake(2, 3))
    }

    @Test
    fun retakeWithoutIndexUsesLastShot() = runTest {
        val b = booth()
        repeat(2) { b.requestCapture(0); advanceUntilIdle() }
        assertEquals("2번째 컷, 3초 뒤에 찍을게요", b.retake(null, 3))
    }

    @Test
    fun cancelRetakeReturnsToOriginal() = runTest {
        val b = booth()
        b.requestCapture(0); advanceUntilIdle()
        b.retake(0, 3)
        assertEquals("원래 컷으로 돌아왔어요.", b.cancelCapture())
        advanceUntilIdle()
        assertEquals("shot0", b.state.value.shots[0])
        assertNull(b.state.value.retake)
    }

    @Test
    fun retakeAfterAllFourReportsReplaced() = runTest {
        val b = booth()
        repeat(4) { b.requestCapture(0); advanceUntilIdle() }
        b.retake(3, 0); advanceUntilIdle()
        assertEquals("다시 찍은 컷으로 바꿨어요.", b.state.value.reply)
    }

    @Test
    fun layoutOnlyBeforeFirstCut() = runTest {
        val b = booth()
        assertEquals("이미 2×2 네컷을 선택했어요", b.setLayout(LayoutId.GRID))
        assertEquals("1×4 세로 네컷으로 바꿨어요", b.setLayout(LayoutId.STRIP))
        assertEquals(LayoutId.STRIP, b.state.value.layout)
        b.requestCapture(0); advanceUntilIdle()
        assertEquals("네컷 모양은 첫 컷을 찍기 전에만 바꿀 수 있어요", b.setLayout(LayoutId.GRID))
        assertEquals(LayoutId.STRIP, b.state.value.layout)
    }

    @Test
    fun restartClearsShotsKeepsLayoutAndCamera() = runTest {
        val b = booth()
        b.setLayout(LayoutId.STRIP)
        repeat(2) { b.requestCapture(0); advanceUntilIdle() }
        b.requestCapture(3)
        val first = PosePlanner.recommendPlan(TestPoses.catalog.poses(1), null)[0][0].name
        assertEquals("새로 시작할게요. 첫 번째는 $first", b.restart())
        advanceUntilIdle()
        val s = b.state.value
        assertEquals(List(4) { null }, s.shots)
        assertFalse(s.counting)
        assertTrue(s.cameraOn)
        assertEquals(LayoutId.STRIP, s.layout)
    }

    @Test
    fun executorRoutesAndReplies() = runTest {
        val b = booth()
        val ex = CommandExecutor(b)
        assertEquals("1번째 컷, 3초 뒤에 찍을게요", ex.execute(Command.Capture(3)))
        assertEquals("1번째 컷, 3초 뒤에 찍을게요", b.state.value.reply)
        assertEquals("촬영을 멈췄어요", ex.execute(Command.Cancel))
        assertEquals("1×4 세로 네컷으로 바꿨어요", ex.execute(Command.Layout(LayoutId.STRIP)))
    }

    @Test
    fun filterCommandsUpdateParamsAndMood() = runTest {
        val b = booth()
        val ex = CommandExecutor(b)
        val warm = moodById("warm")!!
        assertEquals("따뜻한 느낌으로 색온도 +35, 페이드 +10, 밝기 +5, 채도 +5", ex.execute(Command.Filter(EmotionInterpreter.moodChip(warm))))
        assertEquals(35, b.state.value.params[Param.WARMTH])
        assertEquals("warm", b.state.value.moodId)

        ex.execute(Command.Filter(EmotionInterpreter.nudge(warm, less = false)))
        assertEquals(35 + 15, b.state.value.params[Param.WARMTH])
        assertEquals("warm", b.state.value.moodId)

        assertEquals("보정을 껐어요", ex.execute(Command.ResetFilter))
        assertTrue(b.state.value.params.isNeutral)
        assertNull(b.state.value.moodId)
    }

    @Test
    fun restartClearsFilter() = runTest {
        val b = booth()
        b.applyFilter(EmotionInterpreter.moodChip(moodById("mono")!!))
        b.restart()
        assertTrue(b.state.value.params.isNeutral)
        assertNull(b.state.value.moodId)
    }

    @Test
    fun headcountReplansAndPoseNextCycles() = runTest {
        val b = booth()
        assertEquals(1, b.state.value.headcount)
        assertEquals("3명에 맞춰 포즈를 골랐어요", b.setHeadcount(3))
        val plan3 = PosePlanner.recommendPlan(TestPoses.catalog.poses(3), null)
        assertEquals(plan3, b.state.value.plan)
        assertEquals(plan3[0][1].name.let { "그럼 $it 어때요" }, b.poseNext())
        assertEquals(plan3[0][1], b.state.value.currentPose)
        assertEquals("4명에 맞춰 포즈를 골랐어요", b.setHeadcount(9))
    }

    @Test
    fun poseFollowsCurrentCutAndRetake() = runTest {
        val b = booth()
        val plan = b.state.value.plan
        b.requestCapture(0); advanceUntilIdle()
        assertEquals(plan[1][0], b.state.value.currentPose)
        b.retake(0, 3)
        assertEquals(plan[0][0], b.state.value.currentPose)
        b.cancelCapture()
        repeat(3) { b.requestCapture(0); advanceUntilIdle() }
        assertNull(b.state.value.currentPose)
        assertEquals("네 컷을 다 찍었어요", b.poseNext())
    }

    @Test
    fun moodChangeReplansOnlyUnshotCuts() = runTest {
        val b = booth()
        b.setHeadcount(2)
        b.requestCapture(0); advanceUntilIdle()
        val shotPlan = b.state.value.plan[0]
        b.applyFilter(EmotionInterpreter.moodChip(moodById("chic")!!))
        val chic = PosePlanner.recommendPlan(TestPoses.catalog.poses(2), "chic")
        assertEquals(shotPlan, b.state.value.plan[0])
        assertEquals(chic.drop(1), b.state.value.plan.drop(1))
        // 같은 분위기에서 조금 더 → 다시 추천하지 않는다
        b.poseNext()
        val pick = b.state.value.pick
        b.applyFilter(EmotionInterpreter.nudge(moodById("chic")!!, less = false))
        assertEquals(pick, b.state.value.pick)
    }

    // ---------- Step 4: 프레임 ----------

    private class FakeFrames(var avg: DoubleArray? = null, var ok: Boolean = true) : FrameTools<String> {
        val saved = mutableListOf<BoothState<String>>()
        override suspend fun averageColor(shots: List<String?>, params: com.byyourside.talkandcut.filter.FilterParams) =
            if (shots.any { it != null }) avg else null
        override suspend fun save(state: BoothState<String>): Boolean { saved += state; return ok }
    }

    private fun TestScope.framedBooth(frames: FakeFrames): Booth<String> =
        Booth(this, { "shot${counter++}" }, TestPoses.catalog, frames = frames).also { it.setCameraOn(true) }

    @Test
    fun fourthCutAppliesTopFrameAndOpensResult() = runTest {
        // 밝은 무채색 사진 → 클래식 블랙이 1위 (ThemesTest와 같은 입력)
        val b = framedBooth(FakeFrames(avg = doubleArrayOf(0.8, 0.8, 0.82)))
        repeat(3) { b.requestCapture(0); advanceUntilIdle() }
        assertFalse(b.state.value.resultOpen)
        assertEquals("white", b.state.value.themeId)
        b.requestCapture(0); advanceUntilIdle()
        val s = b.state.value
        assertTrue(s.resultOpen)
        assertEquals("black", s.themeId)
        assertEquals("black", s.recs[0].themeId)
        assertEquals("네 컷을 다 찍었어요", s.reply)
    }

    @Test
    fun retakeFromResultClosesAndKeepsChosenFrame() = runTest {
        val b = framedBooth(FakeFrames(avg = doubleArrayOf(0.8, 0.8, 0.82)))
        repeat(4) { b.requestCapture(0); advanceUntilIdle() }
        val ex = CommandExecutor(b)
        ex.execute(Command.Theme("peach"))
        ex.execute(Command.Retake(1, 0))
        assertFalse(b.state.value.resultOpen)
        advanceUntilIdle()
        assertTrue(b.state.value.resultOpen)
        assertEquals("peach", b.state.value.themeId)
        assertEquals("다시 찍은 컷으로 바꿨어요.", b.state.value.reply)
    }

    @Test
    fun cancelRetakeReopensResult() = runTest {
        val b = framedBooth(FakeFrames())
        repeat(4) { b.requestCapture(0); advanceUntilIdle() }
        b.retake(2, 3)
        assertFalse(b.state.value.resultOpen)
        assertEquals("원래 컷으로 돌아왔어요.", b.cancelCapture())
        assertTrue(b.state.value.resultOpen)
    }

    @Test
    fun frameRecommendAndNextCycleWithinTopFive() = runTest {
        val b = framedBooth(FakeFrames(avg = doubleArrayOf(0.6, 0.5, 0.4)))
        val ex = CommandExecutor(b)
        b.applyFilter(EmotionInterpreter.moodChip(moodById("warm")!!))
        b.requestCapture(0); advanceUntilIdle()
        assertEquals("복숭아 우유 추천해요. 지금 보정한 분위기와 같은 계열", ex.execute(Command.FrameRecommend))
        assertEquals("peach", b.state.value.themeId)
        val names = (1..5).map { ex.execute(Command.FrameNext) }
        assertEquals(
            listOf("벽돌 레트로로 바꿨어요", "숲속 오후로 바꿨어요", "레몬 소다로 바꿨어요", "클래식 화이트로 바꿨어요", "복숭아 우유로 바꿨어요"),
            names,
        )
    }

    @Test
    fun frameNextWithoutRecsRecommendsFirst() = runTest {
        val b = framedBooth(FakeFrames())
        // 추천 전: 점수가 모두 0이라 THEMES 순서, 다음은 두 번째(클래식 블랙)
        assertEquals("클래식 블랙로 바꿨어요", CommandExecutor(b).execute(Command.FrameNext))
    }

    @Test
    fun captionReplies() = runTest {
        val b = framedBooth(FakeFrames())
        val ex = CommandExecutor(b)
        assertEquals("문구를 “우리 우정”로 넣었어요", ex.execute(Command.Caption("우리 우정")))
        assertEquals("우리 우정", b.state.value.caption)
        assertEquals("", ex.execute(Command.Caption("우리", quiet = true)))
        assertEquals("우리", b.state.value.caption)
        assertEquals("문구를 지웠어요", ex.execute(Command.Caption("")))
    }

    @Test
    fun saveReplies() = runTest {
        val frames = FakeFrames()
        val b = framedBooth(frames)
        val ex = CommandExecutor(b)
        assertEquals("아직 찍은 사진이 없어요", ex.execute(Command.Save))
        b.requestCapture(0); advanceUntilIdle()
        assertEquals("찍은 컷까지만 저장했어요", ex.execute(Command.Save))
        advanceUntilIdle()
        assertEquals(1, frames.saved.size)
        repeat(3) { b.requestCapture(0); advanceUntilIdle() }
        assertEquals("사진을 저장했어요", ex.execute(Command.Save))
        frames.ok = false
        ex.execute(Command.Save)
        advanceUntilIdle()
        assertEquals("사진을 저장하지 못했어요. 다시 시도해 주세요", b.state.value.reply)
    }

    @Test
    fun restartResetsFrameState() = runTest {
        val b = framedBooth(FakeFrames())
        repeat(4) { b.requestCapture(0); advanceUntilIdle() }
        b.setTheme("lemon")
        b.setCaption("안녕")
        b.restart()
        val s = b.state.value
        assertEquals("white", s.themeId)
        assertEquals("", s.caption)
        assertTrue(s.recs.isEmpty())
        assertFalse(s.resultOpen)
    }

    @Test
    fun resultNeedsAShot() = runTest {
        val b = framedBooth(FakeFrames())
        assertEquals("아직 찍은 사진이 없어요", b.showResult(true))
        b.requestCapture(0); advanceUntilIdle()
        b.showResult(true)
        assertTrue(b.state.value.resultOpen)
        b.showResult(false)
        assertFalse(b.state.value.resultOpen)
    }

    @Test
    fun failedCaptureKeepsCutEmpty() = runTest {
        val b = Booth<String>(this, { null }, TestPoses.catalog).also { it.setCameraOn(true) }
        b.requestCapture(0); advanceUntilIdle()
        assertNull(b.state.value.shots[0])
        assertFalse(b.state.value.shutterOn)
        assertEquals("카메라를 준비 중이에요. 잠시 후 다시 찍어 주세요", b.state.value.reply)
    }
}
