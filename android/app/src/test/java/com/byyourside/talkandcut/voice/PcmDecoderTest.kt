package com.byyourside.talkandcut.voice

import org.junit.Assert.*
import org.junit.Test

class PcmDecoderTest {
    @Test fun preservesEverySourceSampleForNativeResampling() {
        val bytes = ByteArray(48000 * 2)
        assertEquals(48000, PcmDecoder.mono(bytes, 1, 16).size)
    }
    @Test fun decodesSignedPcmAndDownmixesChannels() {
        assertArrayEquals(floatArrayOf(0.5f, -0.5f), PcmDecoder.mono(byteArrayOf(0, 0x40, 0, 0xC0.toByte()), 1, 16), 0.0001f)
        assertArrayEquals(floatArrayOf(0f), PcmDecoder.mono(byteArrayOf(0, 0x40, 0, 0xC0.toByte()), 2, 16), 0.0001f)
    }
}
