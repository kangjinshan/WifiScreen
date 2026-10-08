package com.kanayama.wifiscreen

data class PcmBlock(val mediaSample: Long, val data: ByteArray)

/** Only a confirmed media-sample gap can modify PCM. Arrival timing cannot replace real audio. */
class AudioContinuity(private val rate: Int, private val channels: Int) {
    private val wave = PcmConcealer(rate, channels)
    private val frameBytes = channels * 2
    private val budget = rate * PlaybackTiming.BUFFER_MS / 1000
    private var nextSample: Long? = null
    var concealedSamples = 0L; private set
    var skippedSamples = 0L; private set
    var overlapSamples = 0L; private set

    fun accept(mediaSample: Long, data: ByteArray): List<PcmBlock> {
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
            if (gap <= budget && wave.available) {
                result.add(PcmBlock(nextSample!!, wave.conceal(gap.toInt())))
                concealedSamples += gap
            } else {
                skippedSamples += gap
            }
            wave.join(pcm)
        }
        result.add(PcmBlock(position, pcm))
        nextSample = position + pcm.size / frameBytes
        wave.remember(pcm)
        return result
    }
}
