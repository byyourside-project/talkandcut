package com.byyourside.talkandcut.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

/** 웹 tests/voice.test.mjs 이식. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceEngineTest {
    private class FakeRecognition : Recognition {
        override var listener: Recognition.Listener? = null
        var starts = 0
        var aborts = 0
        override fun start() { starts++ }
        override fun abort() { aborts++ }
        fun onStart() = listener!!.onStart()
        fun onEnd() = listener!!.onEnd()
        fun onError(code: String) = listener!!.onError(code)
        fun hear(t: String) = listener!!.onResult(t)
    }

    private val created = mutableListOf<FakeRecognition>()
    private val rec get() = created.last()

    private fun TestScope.engine() = VoiceEngine(backgroundScope, supported = true, create = { FakeRecognition().also { created += it } })

    @Test
    fun errorDoesNotAutoRestartButMenuCanReconnect() = runTest {
        val voice = engine()
        voice.start()
        assertEquals(VoiceState.CONNECTING, voice.state.value)
        rec.onStart()
        assertEquals(VoiceState.LISTENING, voice.state.value)

        val denied = rec
        denied.onError("not-allowed")
        denied.onEnd()
        assertEquals(VoiceState.DENIED, voice.state.value)
        assertEquals(1, denied.starts)

        voice.start()
        rec.onStart()
        assertEquals(VoiceState.LISTENING, voice.state.value)
        val retry = rec
        assertNotSame(denied, retry)
        assertEquals(1, retry.starts)
        retry.onError("network")
        retry.onEnd()
        assertEquals(VoiceState.ERROR, voice.state.value)
        assertEquals("network", voice.reason.value)
        assertEquals(1, retry.starts)
    }

    @Test
    fun listensAgainOnlyAfterMicSessionEnds() = runTest {
        val voice = engine()
        val heard = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { voice.transcripts.toList(heard) }
        voice.start()
        rec.onStart()
        voice.pause() // say()
        assertEquals(VoiceState.SPEAKING, voice.state.value)
        assertEquals(1, rec.aborts)
        rec.hear("찍어요")
        assertEquals(emptyList<String>(), heard)

        voice.resume() // spoken.onend
        assertEquals(1, rec.starts)
        rec.onEnd()
        rec.onStart()
        rec.hear("바로 찍어")
        assertEquals(listOf("바로 찍어"), heard)
        assertEquals(2, rec.starts)

        val stopped = rec
        voice.stop()
        stopped.onEnd()
        assertEquals(2, stopped.starts)
        assertEquals(VoiceState.OFF, voice.state.value)
    }

    @Test
    fun startTimeoutAbortsAndIgnoresLateEvents() = runTest {
        val voice = engine()
        val heard = mutableListOf<String>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { voice.transcripts.toList(heard) }
        voice.start()
        val stalled = rec
        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(VoiceState.ERROR, voice.state.value)
        assertEquals("timeout", voice.reason.value)
        assertEquals(1, stalled.aborts)

        voice.start()
        val fresh = rec
        assertNotSame(stalled, fresh)
        stalled.onStart()
        stalled.onEnd()
        stalled.hear("찍어줘")
        assertEquals(VoiceState.CONNECTING, voice.state.value)
        fresh.onStart()
        assertEquals(VoiceState.LISTENING, voice.state.value)
        // 시작 타이머가 지워져서 더 기다려도 timeout이 나지 않는다
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(VoiceState.LISTENING, voice.state.value)
        assertEquals(emptyList<String>(), heard)
        voice.stop()
    }

    @Test
    fun closedBeforeStartDoesNotRestart() = runTest {
        val voice = engine()
        voice.start()
        val stalled = rec
        stalled.onEnd()
        assertEquals(VoiceState.ERROR, voice.state.value)
        assertEquals("start-ended", voice.reason.value)
        assertEquals(1, stalled.starts)
    }

    @Test
    fun unsupported() = runTest {
        val voice = VoiceEngine(backgroundScope, supported = false, create = { error("no") })
        voice.start()
        assertEquals(VoiceState.UNSUPPORTED, voice.state.value)
    }
}
