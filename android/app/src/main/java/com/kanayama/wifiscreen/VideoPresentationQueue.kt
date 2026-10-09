package com.kanayama.wifiscreen

import java.util.ArrayDeque

data class VideoOutputFrame(
    val index: Int,
    val ptsUs: Long,
    val receivedNs: Long?,
    val decodedNs: Long,
    val width: Int,
    val height: Int,
    val fallbackNs: Long?,
    var targetNs: Long?,
    var usesAudioClock: Boolean,
    var syncDrop: Boolean = false,
    var earlyForThroughput: Boolean = false
)

/** Owns at most four dequeued codec buffers; callers release every returned frame exactly once. */
class VideoPresentationQueue(private val capacity: Int = 4, private val maxHoldNs: Long = PlaybackTiming.BUFFER_NS) {
    private val frames = ArrayDeque<VideoOutputFrame>()
    init { require(capacity > 0) }
    val size: Int get() = frames.size
    fun first(): VideoOutputFrame? = frames.peekFirst()

    /** A full queue releases its oldest buffer early instead of blocking the decoder. */
    fun offer(frame: VideoOutputFrame): VideoOutputFrame? {
        val displaced = if (frames.size == capacity) frames.removeFirst() else null
        frames.addLast(frame)
        return displaced
    }
    fun poll(nowNs: Long, force: Boolean = false): VideoOutputFrame? {
        val first = frames.peekFirst() ?: return null
        val target = first.targetNs
        if (!force && target != null && target > nowNs && nowNs - first.decodedNs < maxHoldNs) return null
        return frames.removeFirst()
    }
    fun waitMs(nowNs: Long): Long {
        val first = frames.peekFirst() ?: return 5
        val target = first.targetNs ?: return 0
        return minOf(target - nowNs, first.decodedNs + maxHoldNs - nowNs).coerceIn(0, 5_000_000L) / 1_000_000L
    }
    fun clear(): List<VideoOutputFrame> = frames.toList().also { frames.clear() }
}
