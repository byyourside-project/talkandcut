package com.byyourside.talkandcut.devices

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** PCM stream passed to SpeechRecognizer on Android 13+, with an explicit input device. */
class MicrophonePipe {
    private val running = AtomicBoolean(false)
    private var recorder: AudioRecord? = null
    private var read: ParcelFileDescriptor? = null
    private var write: ParcelFileDescriptor? = null
    private var workerStarted = false
    @SuppressLint("MissingPermission")
    fun start(settings: DeviceSettings): ParcelFileDescriptor {
        val device = settings.microphone() ?: error("선택한 마이크 연결이 끊겼어요")
        val size = maxOf(4096, AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))
        val record = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build()).setBufferSizeInBytes(size * 2).build()
        recorder = record
        check(record.state == AudioRecord.STATE_INITIALIZED && record.setPreferredDevice(device)) { "선택한 마이크를 열 수 없어요" }
        val pipe = ParcelFileDescriptor.createPipe()
        read = pipe[0]; write = pipe[1]
        record.startRecording()
        running.set(true)
        workerStarted = true
        thread(name = "selected-microphone") {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                    val buffer = ByteArray(size)
                    while (running.get()) {
                        val count = record.read(buffer, 0, buffer.size)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                    }
                }
            } catch (_: Exception) {
                // Recognition finished or was cancelled; the pipe is no longer consumed.
            } finally { record.release() }
        }
        return pipe[0]
    }
    fun close() {
        running.set(false)
        runCatching { recorder?.stop() }
        if (!workerStarted) runCatching { recorder?.release() }
        runCatching { read?.close() }
        runCatching { write?.close() }
        recorder = null; read = null; write = null
    }
}
