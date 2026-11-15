package com.byyourside.talkandcut.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceSegmenterTest {
    @Test fun doesNotLearnAnImmediateWordAsAmbientNoise() {
        val segmenter = VoiceSegmenter()
        // Simulate the neural detector's initial lookahead before it recognizes a word.
        repeat(8) { assertNull(segmenter.accept(FloatArray(160) { 0.012f }, false)) }
        repeat(92) { assertNull(segmenter.accept(FloatArray(160) { 0.012f }, true)) }
        var output: FloatArray? = null
        repeat(30) { segmenter.accept(FloatArray(160) { 0.001f }, false)?.let { output = it } }
        assertNotNull(output)
        assertTrue(output!!.size >= 16000)
    }
    @Test fun learnsLouderUsbAmbientInsteadOfQueuingEveryFourSeconds() {
        val segmenter = VoiceSegmenter()
        repeat(1000) { assertNull(segmenter.accept(FloatArray(160) { 0.008f }, false)) }
        assertFalse(segmenter.speaking)
        repeat(40) { assertNull(segmenter.accept(FloatArray(160) { 0.04f }, true)) }
        var output: FloatArray? = null
        repeat(30) { segmenter.accept(FloatArray(160) { 0.008f }, false)?.let { output = it } }
        assertNotNull(output)
    }
    @Test fun endsAfterQuarterSecondWithoutClippingTheWord() {
        val segmenter = VoiceSegmenter()
        repeat(25) { segmenter.accept(FloatArray(160) { 0.001f }, false) }
        repeat(100) { assertNull(segmenter.accept(FloatArray(160) { 0.012f }, false)) }
        repeat(24) { assertNull(segmenter.accept(FloatArray(160) { 0.001f }, false)) }
        val output = segmenter.accept(FloatArray(160) { 0.001f }, false)
        assertNotNull(output)
        assertTrue(output!!.size >= 16000)
    }
    @Test fun keepsShortQuietCommandWithoutNeuralDetection() {
        val segmenter = VoiceSegmenter()
        repeat(20) { assertNull(segmenter.accept(FloatArray(160) { 0.001f }, false)) }
        repeat(25) { assertNull(segmenter.accept(FloatArray(160) { 0.012f }, false)) }
        var output: FloatArray? = null
        repeat(60) { segmenter.accept(FloatArray(160) { 0.001f }, false)?.let { output = it } }
        assertNotNull(output)
        assertTrue(output!!.size >= 4000)
    }
    @Test fun ignoresAmbientNoiseAndSingleClicks() {
        val segmenter = VoiceSegmenter()
        repeat(100) { assertNull(segmenter.accept(FloatArray(160) { 0.001f }, false)) }
        assertNull(segmenter.accept(FloatArray(160) { 0.2f }, false))
        repeat(100) { assertNull(segmenter.accept(FloatArray(160) { 0.001f }, false)) }
    }
    @Test fun neuralSpeechCanTriggerEvenBelowEnergyThreshold() {
        val segmenter = VoiceSegmenter()
        repeat(30) { segmenter.accept(FloatArray(160) { 0.003f }, true) }
        assertTrue(segmenter.speaking)
        var output: FloatArray? = null
        repeat(60) { segmenter.accept(FloatArray(160), false)?.let { output = it } }
        assertNotNull(output)
    }
}
