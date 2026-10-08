package com.kanayama.wifiscreen

data class PcmBlock(val mediaSample: Long, val data: ByteArray)

/** Repairs at most 20ms per outage, then joins the next available media interval. */
class AudioContinuity(private val rate: Int, private val channels: Int) {
    private val wave = PcmConcealer(rate, channels)
    private val frameBytes = channels * 2
    private val budget = rate * PlaybackTiming.BUFFER_MS / 1000
    private val packetFrames = AudioSampleClock.SAMPLES_PER_PACKET * rate / AudioSampleClock.SAMPLE_RATE
    private var nextSample: Long? = null
    private var repaired = 0
    private var recovering = false
    private var lastRealNs: Long? = null
    var concealedSamples = 0L; private set
    var starvationSamples = 0L; private set
    var skippedSamples = 0L; private set
    var overlapSamples = 0L; private set

    fun accept(mediaSample: Long, data: ByteArray, nowNs: Long): List<PcmBlock> {
        require(data.size % frameBytes == 0)
        if (data.isEmpty()) return emptyList()
        if (nextSample == null) nextSample = mediaSample
        val overlap = (nextSample!! - mediaSample).coerceIn(0, (data.size / frameBytes).toLong()).toInt()
        overlapSamples += overlap
        if (overlap * frameBytes == data.size) return emptyList()
        val position = mediaSample + overlap
        val pcm = if (overlap > 0) data.copyOfRange(overlap * frameBytes, data.size) else data
        val gap = position - nextSample!!
        val result = mutableListOf<PcmBlock>()
        if (gap > 0) {
            if (gap <= budget - repaired && wave.available) {
                result.add(repair(gap.toInt(), false))
            } else {
                skippedSamples += gap
                nextSample = position
                recovering = true
            }
        }
        if (recovering) wave.join(pcm)
        result.add(PcmBlock(position, pcm))
        nextSample = position + pcm.size / frameBytes
        wave.remember(pcm)
        repaired = 0; recovering = false; lastRealNs = nowNs
        return result
    }

    fun protectBuffer(bufferedFrames: Long, lowWaterFrames: Int, nowNs: Long, lastPacketNs: Long): PcmBlock? {
        val last = lastRealNs ?: return null
        val packetNs = packetFrames * 1_000_000_000L / rate
        // Decoders may output multiple access units together. Fresh RTP arrivals
        // must not be mistaken for missing audio merely because PCM is batched.
        val packetLate = nowNs - lastPacketNs >= packetNs + 4_000_000L
        val nearlyEmpty = bufferedFrames <= rate * 3L / 1000
        if (!wave.available || repaired >= budget || bufferedFrames > lowWaterFrames ||
            nowNs - last < packetNs || (!packetLate && !nearlyEmpty)) return null
        // A small top-up leaves more of a slightly late real packet intact.
        return repair(minOf(packetFrames, (rate * 5 + 999) / 1000, budget - repaired), true)
    }

    private fun repair(frames: Int, starving: Boolean): PcmBlock {
        val result = PcmBlock(nextSample!!, wave.conceal(frames))
        nextSample = nextSample!! + frames
        repaired += frames; concealedSamples += frames
        if (starving) starvationSamples += frames
        recovering = true
        return result
    }
}
