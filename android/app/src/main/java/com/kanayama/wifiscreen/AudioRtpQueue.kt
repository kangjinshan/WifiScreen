package com.kanayama.wifiscreen

import java.util.TreeMap
import kotlin.math.abs

data class AudioRtpFrame(val sequence: Int, val timestamp: Long, val data: ByteArray, val receivedNs: Long = 0, val ssrc: Long = 0)

/** Reorder within the 20ms budget. Missing/late packets are counted instead of hidden. */
class AudioRtpQueue(private val capacity: Int = 32) {
    private val frames = TreeMap<Long, AudioRtpFrame>()
    private val seen = linkedSetOf<Long>()
    private var highest: Long? = null
    private var expected: Long? = null
    private var waitingSince: Long? = null
    private var nextExpectedArrivalNs: Long? = null
    private var previousArrivalNs: Long? = null
    private var previousTimestamp = 0L
    private var jitterTicks = 0.0
    private var currentSsrc: Long? = null
    @Volatile private var closed = false
    @Volatile var missingPackets = 0L; private set
    @Volatile var reorderedPackets = 0L; private set
    @Volatile var duplicatePackets = 0L; private set
    @Volatile var latePackets = 0L; private set
    @Volatile var overflowPackets = 0L; private set
    @Volatile var jitterMs = 0.0; private set
    val size: Int @Synchronized get() = frames.size

    @Synchronized fun offer(frame: AudioRtpFrame, nowNs: Long) {
        if (closed) return
        if (currentSsrc != null && currentSsrc != frame.ssrc) {
            frames.clear(); seen.clear(); highest = null; expected = null
            waitingSince = null; nextExpectedArrivalNs = null; previousArrivalNs = null; jitterTicks = 0.0
        }
        currentSsrc = frame.ssrc
        val high = highest
        var sequence = if (high == null) frame.sequence.toLong() else (high and -65536L) or frame.sequence.toLong()
        if (high != null) {
            if (sequence - high > 32768) sequence -= 65536
            if (high - sequence > 32768) sequence += 65536
        }
        if (sequence in seen) { duplicatePackets++; return }
        if (expected?.let { sequence < it } == true) { latePackets++; return }
        seen.add(sequence)
        while (seen.size > 128) seen.remove(seen.first())
        if (high != null && sequence < high) reorderedPackets++
        if (high == null || sequence > high) highest = sequence
        if (expected == null) expected = sequence
        previousArrivalNs?.let { arrival ->
            val timestampDelta = (frame.timestamp - previousTimestamp).toInt().toLong()
            val arrivalTicks = (nowNs - arrival) * 44100.0 / 1_000_000_000.0
            jitterTicks += (abs(arrivalTicks - timestampDelta) - jitterTicks) / 16.0
            jitterMs = jitterTicks * 1000.0 / 44100.0
        }
        previousArrivalNs = nowNs
        previousTimestamp = frame.timestamp
        if (frames.size >= capacity) { overflowPackets++; return }
        frames[sequence] = frame.copy(receivedNs = nowNs)
    }

    @Synchronized fun poll(nowNs: Long): AudioRtpFrame? {
        if (closed || frames.isEmpty()) { waitingSince = null; return null }
        val next = expected ?: return null
        frames.remove(next)?.let {
            expected = next + 1; waitingSince = null
            nextExpectedArrivalNs = it.receivedNs + 480L * 1_000_000_000L / 44100
            return it
        }
        // The 20ms budget starts when the missing packet was due, not one packet later
        // when its successor happens to reveal the gap.
        val since = waitingSince ?: (nextExpectedArrivalNs ?: nowNs).also { waitingSince = it }
        if (nowNs - since < PlaybackTiming.BUFFER_NS) return null
        val available = frames.firstKey()
        missingPackets += (available - next).coerceAtLeast(0)
        expected = available + 1
        waitingSince = null
        return frames.remove(available)?.also {
            nextExpectedArrivalNs = it.receivedNs + 480L * 1_000_000_000L / 44100
        }
    }

    @Synchronized fun close() { closed = true; frames.clear() }
}
