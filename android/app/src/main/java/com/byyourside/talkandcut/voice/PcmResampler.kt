package com.byyourside.talkandcut.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Downmix and resample USB PCM while preserving the phase across callback boundaries. */
class PcmResampler(private val rate: Int, private val channels: Int, private val bits: Int) {
    private var phase = 0L
    // Decimating without a low-pass filter aliases USB microphone noise into speech.
    // A Blackman-windowed sinc preserves speech while rejecting frequencies above 8 kHz.
    private val taps = if (rate > 16000) FloatArray(127).also { coefficients ->
        val cutoff = 0.45 * 16000 / rate
        for (i in coefficients.indices) {
            val x = i - (coefficients.size - 1) / 2.0
            val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
            val window = 0.42 - 0.5 * cos(2 * PI * i / (coefficients.size - 1)) + 0.08 * cos(4 * PI * i / (coefficients.size - 1))
            coefficients[i] = (sinc * window).toFloat()
        }
        val sum = coefficients.sum()
        for (i in coefficients.indices) coefficients[i] /= sum
    } else floatArrayOf(1f)
    private val history = FloatArray(taps.size)
    private var cursor = 0
    fun convert(bytes: ByteArray): FloatArray {
        val stride = channels * (bits / 8)
        val result = ArrayList<Float>()
        for (offset in 0 until bytes.size - stride + 1 step stride) {
            var sum = 0f
            repeat(channels) { channel ->
                val index = offset + channel * bits / 8
                sum += if (bits == 16) (((bytes[index].toInt() and 255) or (bytes[index + 1].toInt() shl 8)).toShort()) / 32768f
                else ((bytes[index].toInt() and 255) - 128) / 128f
            }
            history[cursor] = sum / channels
            cursor = (cursor + 1) % history.size
            phase += 16000
            if (phase >= rate) {
                var filtered = 0f
                var index = cursor
                for (tap in taps) {
                    index = if (index == 0) history.lastIndex else index - 1
                    filtered += tap * history[index]
                }
                while (phase >= rate) { result.add(filtered); phase -= rate }
            }
        }
        return result.toFloatArray()
    }
}
