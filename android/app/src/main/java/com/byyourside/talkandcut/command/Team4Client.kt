package com.byyourside.talkandcut.command

import android.content.*
import android.os.*
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class Team4Client(private val context: Context) {
    private var connected = CompletableDeferred<Messenger>()
    private var bound = false
    private val sequence = AtomicInteger()
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<String>>()
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        pending.remove(message.arg1)?.let { response ->
            if (message.what == 0) response.complete(message.data.getString("value").orEmpty())
            else response.completeExceptionally(IllegalStateException(message.data.getString("error")))
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) { connected.complete(Messenger(service)) }
        override fun onServiceDisconnected(name: ComponentName) {
            val error = IllegalStateException("NPU 모델 프로세스 연결이 끊겼어요")
            pending.values.forEach { it.completeExceptionally(error) }; pending.clear()
            connected = CompletableDeferred()
        }
    }
    suspend fun prepare() { request(1, "") }
    suspend fun query(prompt: String): String = request(2, prompt)
    private suspend fun request(kind: Int, prompt: String): String = withTimeout(90000) {
        withContext(Dispatchers.Main.immediate) {
            if (!bound) {
                bound = context.bindService(Intent(context, Team4Service::class.java), connection,
                    Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
                check(bound) { "Team4 모델 서비스 연결 실패" }
            }
            val remote = connected.await()
            val id = sequence.incrementAndGet()
            val response = CompletableDeferred<String>()
            pending[id] = response
            try {
                remote.send(Message.obtain(null, kind, id, 0).apply {
                    data = Bundle().apply { putString("prompt", prompt) }
                    replyTo = replies
                })
                response.await()
            } finally { pending.remove(id) }
        }
    }
    fun close() {
        if (bound) context.unbindService(connection)
        bound = false
        pending.values.forEach { it.cancel() }; pending.clear()
    }
}
