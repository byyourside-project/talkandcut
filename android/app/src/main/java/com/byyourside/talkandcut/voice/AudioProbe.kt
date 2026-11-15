package com.byyourside.talkandcut.voice

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A bounded debug-only recording to compare USB PCM with the exact decoder input. */
class AudioProbe(private val context: Context) {
    private val raw = ByteArrayOutputStream()
    private val processed = ByteArrayOutputStream()
    private var start = 0L
    fun accept(bytes: ByteArray, samples: FloatArray, rate: Int, channels: Int, bits: Int): Boolean {
        if (start == 0L) start = SystemClock.elapsedRealtime()
        raw.write(bytes)
        val pcm = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        samples.forEach { pcm.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        processed.write(pcm.array())
        val elapsed = SystemClock.elapsedRealtime() - start
        if (elapsed < 6500 && raw.size() < 4_000_000) return false
        writeWave(File(context.filesDir, "audio-probe-raw.wav"), raw.toByteArray(), rate, channels, bits)
        writeWave(File(context.filesDir, "audio-probe-16k.wav"), processed.toByteArray(), 16000, 1, 16)
        Log.i("OfflineSpeech", "Audio probe complete: wallMs=$elapsed rawBytes=${raw.size()} rate=$rate channels=$channels bits=$bits decodedSamples=${processed.size() / 2}")
        return true
    }
    private fun writeWave(file: File, data: ByteArray, rate: Int, channels: Int, bits: Int) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVEfmt ".toByteArray())
        header.putInt(16).putShort(1).putShort(channels.toShort()).putInt(rate)
        header.putInt(rate * channels * bits / 8).putShort((channels * bits / 8).toShort()).putShort(bits.toShort())
        header.put("data".toByteArray()).putInt(data.size)
        file.outputStream().use { it.write(header.array()); it.write(data) }
    }
}
