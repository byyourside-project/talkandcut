package com.byyourside.talkandcut.command

import android.app.Service
import android.content.Intent
import android.os.*
import kotlinx.coroutines.*

/** Keep the vendor NPU runtime in its own process so a native failure cannot close the booth. */
class Team4Service : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: Team4Runtime
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        val id = message.arg1
        val kind = message.what
        val prompt = message.data.getString("prompt").orEmpty()
        val reply = message.replyTo
        scope.launch {
            val response = Message.obtain(null, 0, id, 0)
            try {
                val value = if (kind == 1) { runtime.prepare(); "ready" } else runtime.query(prompt)
                response.data = Bundle().apply { putString("value", value) }
            } catch (e: CancellationException) { throw e
            } catch (e: Throwable) {
                android.util.Log.e("Team4Npu", "NPU request failed", e)
                response.what = -1
                response.data = Bundle().apply { putString("error", e.message ?: e.javaClass.simpleName) }
            }
            runCatching { reply.send(response) }
        }
        true
    })
    override fun onCreate() { super.onCreate(); runtime = Team4Runtime(this) }
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onDestroy() { scope.cancel(); runtime.close(); super.onDestroy() }
}
