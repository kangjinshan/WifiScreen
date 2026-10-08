package com.kanayama.wifiscreen

/** Media time for the negotiated AAC-ELD format: one 480-sample access unit per RTP packet. */
class AudioSampleClock {
    private var previousSequence: Int? = null
    private var previousTimestamp = 0L
    private var samples = 0L
    @Volatile var timestampFallbacks = 0L
        private set

    // Keep the diagnostic total across an SSRC restart within the same session.
    fun reset() { previousSequence = null; previousTimestamp = 0; samples = 0 }

    fun position(sequence: Int, timestamp: Long): Long {
        previousSequence?.let { previous ->
            val sequenceDelta = (sequence - previous) and 65535
            val timestampDelta = (timestamp - previousTimestamp) and 0xffffffffL
            val duration = sequenceDelta * SAMPLES_PER_PACKET.toLong()
            // Some legacy senders use a different clock unit or byte order here.
            // Trust the fixed access-unit duration, including lost packet slots,
            // so those timestamps cannot repeatedly flush the playback preroll.
            if (timestampDelta != duration) timestampFallbacks++
            samples += duration
        }
        previousSequence = sequence
        previousTimestamp = timestamp
        return samples
    }

    companion object {
        const val SAMPLE_RATE = 44100
        const val SAMPLES_PER_PACKET = 480
    }
}
