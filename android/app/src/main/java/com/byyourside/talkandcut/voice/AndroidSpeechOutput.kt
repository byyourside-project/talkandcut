package com.byyourside.talkandcut.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/** TextToSpeech 한국어, 속도 1.1(웹 SpeechSynthesisUtterance rate). say는 다 읽을 때까지 기다린다. */
class AndroidSpeechOutput(context: Context) : SpeechOutput {
    private val ready = CompletableDeferred<Boolean>()
    private val waiting = ConcurrentHashMap<String, Continuation<Unit>>()
    private var seq = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready.complete(status == TextToSpeech.SUCCESS)
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) = finish(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finish(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = finish(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finish(utteranceId)
        })
    }

    private var configured = false

    private fun finish(id: String) {
        waiting.remove(id)?.resume(Unit)
    }

    override suspend fun say(text: String) {
        val ok = withTimeoutOrNull(3000) { ready.await() } ?: false
        if (!ok) return
        if (!configured) {
            if (tts.setLanguage(Locale.KOREAN) < TextToSpeech.LANG_AVAILABLE) return
            tts.setSpeechRate(1.1f)
            configured = true
        }
        val id = "u${++seq}"
        suspendCancellableCoroutine { cont ->
            waiting[id] = cont
            cont.invokeOnCancellation {
                waiting.remove(id)
                tts.stop()
            }
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finish(id)
        }
    }

    override fun cancel() {
        tts.stop()
        // stop()은 onStop을 부르지 않는 엔진도 있어서 기다리는 쪽을 직접 풀어 준다
        waiting.keys.toList().forEach(::finish)
    }

    fun release() {
        cancel()
        tts.shutdown()
    }
}
