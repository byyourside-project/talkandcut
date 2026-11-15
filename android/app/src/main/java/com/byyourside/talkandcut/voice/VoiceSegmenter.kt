package com.byyourside.talkandcut.voice

import kotlin.math.max
import kotlin.math.sqrt

/** Retain short, quiet commands even when a neural VAD misses their first syllable. */
class VoiceSegmenter(private val rate: Int = 16000) {
    private val preRoll = ArrayDeque<Float>()
    private val utterance = ArrayList<Float>()
    private var noise = 0.0015f
    private var calibrationSamples = 0
    private val calibrationLevels = ArrayList<Float>()
    private var speechDuringCalibration = false
    private var voiced = 0
    private var silence = 0
    var speaking = false; private set

    fun accept(samples: FloatArray, neuralSpeech: Boolean): FloatArray? {
        if (samples.isEmpty()) return null
        val rms = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size).toFloat()
        val calibrated = calibrationSamples >= rate / 5
        if (!calibrated) {
            calibrationSamples += samples.size
            if (neuralSpeech) speechDuringCalibration = true
            if (!neuralSpeech) calibrationLevels.add(rms)
            if (calibrationSamples >= rate / 5 && calibrationLevels.isNotEmpty() && !speechDuringCalibration) {
                // USB microphones have different idle gains. A fixed 0.0035 threshold
                // treated this board's steady 0.008 background as endless speech.
                val levels = calibrationLevels.sorted()
                noise = levels[levels.size / 5].coerceAtLeast(0.0005f)
            }
            if (calibrationSamples >= rate / 5) calibrationLevels.clear()
        }
        // Neural speech remains available during the brief ambient calibration.
        val voice = (neuralSpeech && rms > max(0.002f, noise * 1.6f)) ||
            (calibrated && rms > max(0.0035f, noise * 3f))
        if (!voice && !speaking) noise += 0.025f * (rms - noise)
        if (!speaking) {
            samples.forEach { preRoll.addLast(it) }
            while (preRoll.size > rate / 3) preRoll.removeFirst()
            if (!voice) return null
            speaking = true
            utterance.addAll(preRoll)
            preRoll.clear()
        } else samples.forEach { utterance.add(it) }
        if (voice) { voiced += samples.size; silence = 0 } else silence += samples.size
        if (silence < rate * 0.25 && utterance.size < rate * 4) return null
        val result = if (voiced >= rate * 0.12) utterance.toFloatArray() else null
        utterance.clear(); voiced = 0; silence = 0; speaking = false
        return result
    }
}
