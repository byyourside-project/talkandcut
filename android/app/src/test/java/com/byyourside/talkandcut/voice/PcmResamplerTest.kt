package com.byyourside.talkandcut.voice

import org.junit.Assert.*
import org.junit.Test

class PcmResamplerTest {
    @Test fun preservesDurationAcrossUsbPackets() {
        val converter = PcmResampler(48000, 1, 16)
        var count = 0
        repeat(1000) { count += converter.convert(ByteArray(96)).size }
        assertEquals(16000, count)
    }
    @Test fun decodesSignedLittleEndianPcm() {
        val converted = PcmResampler(16000, 1, 16).convert(byteArrayOf(0, 0x40, 0, 0xC0.toByte()))
        assertArrayEquals(floatArrayOf(0.5f, -0.5f), converted, 0.0001f)
    }
    @Test fun averagesStereoChannels() {
        assertEquals(0f, PcmResampler(16000, 2, 16).convert(byteArrayOf(0, 0x40, 0, 0xC0.toByte()))[0], 0.0001f)
    }
    private fun tone(hz: Double): FloatArray {
        val pcm = java.nio.ByteBuffer.allocate(48000 * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(48000) { i -> pcm.putShort((kotlin.math.sin(2 * Math.PI * hz * i / 48000) * 16000).toInt().toShort()) }
        return PcmResampler(48000, 1, 16).convert(pcm.array()).drop(200).toFloatArray()
    }
    private fun rms(samples: FloatArray) = kotlin.math.sqrt(samples.sumOf { (it * it).toDouble() } / samples.size)
    @Test fun rejectsHighFrequencyUsbNoiseBeforeDecimation() {
        assertTrue(rms(tone(12000.0)) < 0.002)
        assertTrue(rms(tone(1000.0)) > 0.30)
    }
}
