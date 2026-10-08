package com.kanayama.wifiscreen

/** Unwrap RTP sample timestamps; tolerate senders that leave the timestamp field constant. */
class AudioSampleClock {
    private var previousSequence: Int? = null
    private var previousTimestamp = 0L
    private var samples = 0L

    fun reset() { previousSequence = null; previousTimestamp = 0; samples = 0 }

    fun position(sequence: Int, timestamp: Long): Long {
        previousSequence?.let { previous ->
            val sequenceDelta = (sequence - previous) and 65535
            val timestampDelta = (timestamp - previousTimestamp) and 0xffffffffL
            samples += if (timestampDelta == 0L || timestampDelta > 0x7fffffffL)
                sequenceDelta * 480L else timestampDelta
        }
        previousSequence = sequence
        previousTimestamp = timestamp
        return samples
    }
}
