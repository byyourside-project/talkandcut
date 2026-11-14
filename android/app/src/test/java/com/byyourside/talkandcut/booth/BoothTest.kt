package com.byyourside.talkandcut.booth

import com.byyourside.talkandcut.filter.EmotionInterpreter
import com.byyourside.talkandcut.filter.Param
import com.byyourside.talkandcut.filter.moodById
import com.byyourside.talkandcut.frame.LayoutId
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
        Booth<String>(this, { "shot${counter++}" }).also { it.setCameraOn(true) }

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
        assertEquals("새로 시작할게요.", b.restart())
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
    fun failedCaptureKeepsCutEmpty() = runTest {
        val b = Booth<String>(this, { null }).also { it.setCameraOn(true) }
        b.requestCapture(0); advanceUntilIdle()
        assertNull(b.state.value.shots[0])
        assertFalse(b.state.value.shutterOn)
        assertEquals("카메라를 준비 중이에요. 잠시 후 다시 찍어 주세요", b.state.value.reply)
    }
}
