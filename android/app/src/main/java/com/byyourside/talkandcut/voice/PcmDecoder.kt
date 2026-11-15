package com.byyourside.talkandcut.voice

/** Decode USB PCM without changing its sampling rate or dropping audio samples. */
object PcmDecoder {
    fun mono(bytes: ByteArray, channels: Int, bits: Int): FloatArray {
        val stride = channels * bits / 8
        return FloatArray(bytes.size / stride) { frame ->
            var sum = 0f
            repeat(channels) { channel ->
                val index = frame * stride + channel * bits / 8
                sum += if (bits == 16) ((bytes[index].toInt() and 255) or (bytes[index + 1].toInt() shl 8)).toShort() / 32768f
                    else ((bytes[index].toInt() and 255) - 128) / 128f
            }
            sum / channels
        }
    }
}
