package com.kanayama.wifiscreen

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Bounded waveform continuation; keeps channel phase and crossfades into recovered PCM. */
class PcmConcealer(private val rate: Int, private val channels: Int) {
    private val capacity = rate * 40 / 1000
    private val history = ShortArray(capacity * channels)
    private var size = 0
    private var cursor = 0
    private var period: ShortArray? = null
    private var predicted = 0
    val available: Boolean get() = size >= rate * 4 / 1000

    private fun sample(frame: Int, channel: Int): Int =
        history[((cursor - size + frame + capacity) % capacity) * channels + channel].toInt()

    fun remember(pcm: ByteArray) {
        val input = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        while (input.remaining() >= channels * 2) {
            for (channel in 0 until channels) history[cursor * channels + channel] = input.short
            cursor = (cursor + 1) % capacity
            size = minOf(capacity, size + 1)
        }
        period = null; predicted = 0
    }

    private fun waveform(): ShortArray {
        period?.let { return it }
        val window = minOf(rate * 4 / 1000, size / 3).coerceAtLeast(1)
        val minimum = minOf(rate * 2 / 1000, size - window).coerceAtLeast(1)
        val maximum = minOf(rate * 15 / 1000, size - window).coerceAtLeast(minimum)
        var lag = minimum
        var best = Double.POSITIVE_INFINITY
        for (candidate in minimum..maximum) {
            var error = 0.0; var energy = 1.0
            for (i in 0 until window step 2) for (channel in 0 until channels) {
                val a = sample(size - window + i, channel).toDouble()
                val b = sample(size - window + i - candidate, channel).toDouble()
                error += (a - b) * (a - b); energy += a * a + b * b
            }
            val score = error / energy
            if (score < best) { best = score; lag = candidate }
        }
        return ShortArray(lag * channels) { i -> sample(size - lag + i / channels, i % channels).toShort() }
            .also { period = it }
    }

    fun conceal(frames: Int): ByteArray {
        require(available && frames >= 0)
        val wave = waveform()
        val periodFrames = wave.size / channels
        val bridge = minOf(rate / 1000, 64).coerceAtLeast(1)
        val budget = (rate * PlaybackTiming.BUFFER_MS / 1000).coerceAtLeast(1)
        val data = ByteArray(frames * channels * 2)
        val output = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        repeat(frames) {
            val taper = budget * 3 / 4
            val gain = if (predicted < taper) 1.0 - 0.1 * predicted / taper
                else 0.9 * (budget - 1 - predicted).coerceAtLeast(0) / (budget - taper)
            for (channel in 0 until channels) {
                var value = wave[(predicted % periodFrames) * channels + channel].toDouble()
                if (predicted < bridge) {
                    value += (sample(size - 1, channel) - wave[channel]) * (bridge - predicted).toDouble() / bridge
                }
                output.putShort((value * gain).toInt().coerceIn(-32768, 32767).toShort())
            }
            predicted++
        }
        return data
    }

    fun join(pcm: ByteArray) {
        if (!available) return
        val frames = minOf(rate * 3 / 1000, pcm.size / (channels * 2))
        if (frames < 2) return
        val continuation = ByteBuffer.wrap(conceal(frames)).order(ByteOrder.LITTLE_ENDIAN)
        val real = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until frames) for (channel in 0 until channels) {
            val offset = (frame * channels + channel) * 2
            val value = (continuation.getShort(offset).toLong() * (frames - 1 - frame) +
                real.getShort(offset).toLong() * frame) / (frames - 1)
            real.putShort(offset, value.toShort())
        }
    }
}
