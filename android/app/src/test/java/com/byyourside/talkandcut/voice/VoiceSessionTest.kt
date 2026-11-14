package com.byyourside.talkandcut.voice

import com.byyourside.talkandcut.booth.Booth
import com.byyourside.talkandcut.command.RuleInterpreter
import com.byyourside.talkandcut.frame.LayoutId
import com.byyourside.talkandcut.pose.PosePlanner
import com.byyourside.talkandcut.pose.TestPoses
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 웹 main.js handleUtterance 규칙. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionTest {
    private class FakeInput : SpeechInput {
        override val state: StateFlow<VoiceState> = MutableStateFlow(VoiceState.LISTENING)
        override val reason: StateFlow<String?> = MutableStateFlow(null)
        override val transcripts = MutableSharedFlow<String>()
        val log = mutableListOf<String>()
        override fun start() { log += "start" }
        override fun stop() { log += "stop" }
        override fun pause() { log += "pause" }
        override fun resume() { log += "resume" }
    }

    private class FakeOutput : SpeechOutput {
        val said = mutableListOf<String>()
        override suspend fun say(text: String) { said += text }
        override fun cancel() {}
    }

    private var counter = 0
    private lateinit var input: FakeInput
    private lateinit var output: FakeOutput
    private lateinit var booth: Booth<String>

    private fun TestScope.session(): VoiceSession {
        input = FakeInput()
        output = FakeOutput()
        booth = Booth(this, { "shot${counter++}" }, TestPoses.catalog).also { it.setCameraOn(true) }
        return VoiceSession(this, input, output, RuleInterpreter, booth, booth.state, booth.announcements)
    }

    @Test
    fun unknownUtteranceGetsHelpReplyAndIsSpoken() = runTest {
        val v = session()
        v.handleUtterance("오늘 점심 뭐 먹지")
        advanceUntilIdle()
        val help = "잘 못 알아들었어요. “찍어줘”나 “따뜻하게”처럼 말해 보세요"
        assertEquals(help, booth.state.value.reply)
        assertEquals(listOf(help), output.said)
        assertEquals(listOf("pause", "resume"), input.log)
    }

    @Test
    fun captureReplyIsNotSpoken() = runTest {
        val v = session()
        v.handleUtterance("찍어줘")
        runCurrent()
        assertEquals("1번째 컷, 3초 뒤에 찍을게요", booth.state.value.reply)
        assertTrue(output.said.isEmpty())
        assertTrue(booth.state.value.counting)
        advanceUntilIdle()
    }

    @Test
    fun announcementsAreSpokenAfterBind() = runTest {
        val v = session()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { booth.announcements.collect { v.say(it) } }
        runCurrent()
        booth.requestCapture(0)
        advanceUntilIdle()
        // 촬영이 끝난 뒤의 안내는 말한다(웹 reply 기본 speak)
        assertEquals(listOf("좋아요. 2번째 컷을 준비해 주세요."), output.said)
    }

    @Test
    fun filterAndCaptureRepliesAreJoined() = runTest {
        val v = session()
        v.handleUtterance("따뜻한 느낌으로 3초 뒤에 찍어줘")
        runCurrent()
        assertEquals("따뜻한 느낌으로 색온도 +35, 페이드 +10, 밝기 +5, 채도 +5. 1번째 컷, 3초 뒤에 찍을게요", booth.state.value.reply)
        assertTrue(output.said.isEmpty())
    }

    @Test
    fun layoutChangeRunsAlone() = runTest {
        val v = session()
        v.handleUtterance("1×4로 바꿔 찍어")
        runCurrent()
        assertEquals(LayoutId.STRIP, booth.state.value.layout)
        assertEquals("1×4 세로 네컷으로 바꿨어요", booth.state.value.reply)
        assertTrue(!booth.state.value.counting)
        advanceUntilIdle()
        assertEquals(listOf("1×4 세로 네컷으로 바꿨어요"), output.said)
    }

    @Test
    fun startDropsCapture() = runTest {
        val v = session()
        v.handleUtterance("처음부터 다시 시작해서 찍어줘")
        runCurrent()
        val first = PosePlanner.recommendPlan(TestPoses.catalog.poses(1), null)[0][0].name
        assertEquals("새로 시작할게요. 첫 번째는 $first", booth.state.value.reply)
        assertTrue(!booth.state.value.counting)
        assertTrue(v.lastJson.value.contains("\"START\"") && !v.lastJson.value.contains("CAPTURE"))
    }

    @Test
    fun ignoredWhileShutterIsOn() = runTest {
        val v = session()
        booth.requestCapture(0)
        runCurrent() // shutterOn, 520ms 대기 중
        assertTrue(booth.state.value.shutterOn)
        v.handleUtterance("저장해줘")
        assertEquals("", v.lastJson.value)
        advanceUntilIdle()
    }

    @Test
    fun poseMatchFallsBackToCountdown() = runTest {
        val v = session()
        v.handleUtterance("포즈 맞추면 찍어줘")
        runCurrent()
        assertEquals("포즈 판정은 지원하지 않아서 3초 뒤에 찍을게요", booth.state.value.reply)
        assertTrue(booth.state.value.counting)
        advanceUntilIdle()
    }

    @Test
    fun completionSentences() = runTest {
        val v = session()
        v.handleUtterance("우리 두 명이야")
        assertEquals("2명에 맞춰 포즈를 골랐어요", booth.state.value.reply)
        v.handleUtterance("다른 포즈")
        assertTrue(booth.state.value.reply.startsWith("그럼 "))
        v.handleUtterance("몽환적인 느낌으로")
        assertTrue(booth.state.value.reply.startsWith("몽환적인 느낌으로"))
        v.handleUtterance("조금 더 따뜻하게")
        assertTrue(booth.state.value.reply.contains("색온도"))
        v.handleUtterance("바로 찍어")
        assertEquals("바로 찍을게요", booth.state.value.reply)
        advanceUntilIdle()
        v.handleUtterance("두 번째 컷 다시 찍어")
        assertEquals("아직 찍지 않은 컷이에요", booth.state.value.reply)
        v.handleUtterance("찍어줘")
        advanceUntilIdle()
        v.handleUtterance("두 번째 컷 다시 찍어")
        assertEquals("2번째 컷, 3초 뒤에 찍을게요", booth.state.value.reply)
        advanceUntilIdle()
        v.handleUtterance("프레임 추천해줘")
        assertTrue(booth.state.value.reply.contains("추천해요"))
        v.handleUtterance("저장해줘")
        assertEquals("찍은 컷까지만 저장했어요", booth.state.value.reply)
        advanceUntilIdle()
    }

    @Test
    fun ttsOffStopsSpeaking() = runTest {
        val v = session()
        v.setTtsEnabled(false)
        v.handleUtterance("오늘 점심 뭐 먹지")
        advanceUntilIdle()
        assertTrue(output.said.isEmpty())
        assertTrue(input.log.isEmpty())
    }
}
