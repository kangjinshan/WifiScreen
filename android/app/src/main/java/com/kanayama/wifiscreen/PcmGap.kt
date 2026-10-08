package com.kanayama.wifiscreen

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Small unrecoverable RTP gaps retain their duration with softened PCM boundaries. */
object PcmGap {
    fun silenceWithFade(frames: Int, previous: ShortArray): ByteArray {
        val data = ByteArray(frames * previous.size * 2)
        val output = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val ramp = minOf(frames, 64)
        for (frame in 0 until ramp) for (sample in previous)
            output.putShort((sample.toInt() * (ramp - frame - 1) / ramp).toShort())
        return data
    }

    fun fadeIn(data: ByteArray, channels: Int) {
        val ramp = minOf(data.size / (channels * 2), 64)
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until ramp) for (channel in 0 until channels) {
            val offset = (frame * channels + channel) * 2
            buffer.putShort(offset, (buffer.getShort(offset).toInt() * frame / ramp).toShort())
        }
    }
}
