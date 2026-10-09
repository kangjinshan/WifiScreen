package com.kanayama.wifiscreen

import java.util.ArrayDeque

object AudioTimestampQuality {
    fun usable(head: Long, frame: Long, written: Long, timestampNs: Long, progressedNs: Long,
        nowNs: Long, rate: Int): Boolean = frame in 0..written && timestampNs > 0 &&
        nowNs - timestampNs in 0..100_000_000L && nowNs - progressedNs in 0..100_000_000L &&
        head - frame in -rate / 50L..rate / 5L
}

/** Maps actual AudioTrack progress to source media time, including skipped intervals. */
class AvPlaybackClock {
    private data class Span(val outputStart: Long, val mediaStart: Long, var frames: Long)
    private data class Skip(val firstSample: Long, val endSample: Long, val outputBoundary: Long)
    private val spans = ArrayDeque<Span>()
    private val skips = ArrayDeque<Skip>()
    private var rate = AudioSampleClock.SAMPLE_RATE
    private var written = 0L
    private var head = 0L
    private var headAtNs = 0L
    private var started = false
    private var audioOriginNs: Long? = null
    private var videoOriginNs: Long? = null
    private var lastVideoPtsUs: Long? = null
    private var lastVideoOutputPtsUs: Long? = null
    private var videoTimestampReliable = true
    private var reportedAudioNs = Long.MIN_VALUE
    @Volatile var rejectedOffsets = 0L
        private set
    @Volatile var lastOffsetMs = 0L
        private set
    private fun sampleTimeNs(sample: Long): Long = sample / rate * 1_000_000_000L +
        sample % rate * 1_000_000_000L / rate

    @Synchronized fun resetAudio(sampleRate: Int) {
        rate = sampleRate; written = 0; head = 0; headAtNs = 0; started = false
        audioOriginNs = null; spans.clear(); skips.clear(); reportedAudioNs = Long.MIN_VALUE
    }
    @Synchronized fun resetVideo() {
        videoOriginNs = null; lastVideoPtsUs = null; lastVideoOutputPtsUs = null; videoTimestampReliable = true
    }
    @Synchronized fun observeVideo(ptsUs: Long, receivedNs: Long) {
        if (lastVideoPtsUs?.let { ptsUs < it } == true) videoOriginNs = null
        val candidate = receivedNs - ptsUs * 1000
        videoOriginNs = minOf(videoOriginNs ?: candidate, candidate)
        lastVideoPtsUs = ptsUs
    }
    @Synchronized fun observeVideoOutput(ptsUs: Long) {
        videoTimestampReliable = lastVideoOutputPtsUs?.let { ptsUs != it } ?: true
        lastVideoOutputPtsUs = ptsUs
    }
    @Synchronized fun observeAudio(mediaSample: Long, receivedNs: Long) {
        val candidate = receivedNs - sampleTimeNs(mediaSample)
        audioOriginNs = minOf(audioOriginNs ?: candidate, candidate)
    }
    @Synchronized fun appendAudio(mediaSample: Long, frames: Int, writtenAtNs: Long? = null) {
        if (frames <= 0) return
        // Time spent with an empty AudioTrack cannot consume newly queued PCM.
        if (started && head == written && writtenAtNs != null) headAtNs = writtenAtNs
        val last = spans.peekLast()
        if (last != null && last.mediaStart + last.frames == mediaSample) last.frames += frames
        else {
            if (last != null && last.mediaStart + last.frames < mediaSample) {
                skips.addLast(Skip(last.mediaStart + last.frames, mediaSample, written))
                while (skips.size > 64) skips.removeFirst()
            }
            spans.addLast(Span(written, mediaSample, frames.toLong()))
        }
        written += frames
    }
    @Synchronized fun updateAudio(playedFrames: Long, nowNs: Long, playing: Boolean) {
        val next = playedFrames.coerceIn(0, written)
        if (next != head || playing != started) headAtNs = nowNs
        head = next; started = playing
        while (spans.size > 1) {
            val first = spans.peekFirst() ?: break
            // Hardware presentation timestamps can trail the client playback
            // head. Keep a short history when switching between those sources.
            if (first.outputStart + first.frames > head - rate / 2) break
            spans.removeFirst()
        }
    }
    @Synchronized fun audioMediaSample(nowNs: Long): Long? {
        if (!started || spans.isEmpty() || nowNs - headAtNs > 100_000_000L) return null
        val frame = minOf(written, head + (nowNs - headAtNs).coerceAtLeast(0) * rate / 1_000_000_000L)
        var result = spans.peekFirst()?.mediaStart ?: return null
        for (span in spans) {
            if (frame < span.outputStart) break
            result = span.mediaStart + (frame - span.outputStart).coerceAtMost(span.frames)
        }
        return result
    }
    @Synchronized fun videoTargetNs(ptsUs: Long, nowNs: Long): Long? {
        if (!videoTimestampReliable) return null
        val videoOrigin = videoOriginNs ?: return null
        val audioOrigin = audioOriginNs ?: return null
        val sample = audioMediaSample(nowNs) ?: return null
        // An exhausted audio queue has no future playback deadline. Let live
        // video continue instead of waiting for the stale-clock timeout.
        if (head + (nowNs - headAtNs).coerceAtLeast(0) * rate / 1_000_000_000L >= written) return null
        val audioNs = maxOf(reportedAudioNs, audioOrigin + sampleTimeNs(sample))
        reportedAudioNs = audioNs
        val skew = videoOrigin + ptsUs * 1000 - audioNs
        lastOffsetMs = skew / 1_000_000L
        // The legacy protocol has no trustworthy common timestamp on every sender.
        // Do not build seconds of delay from a bad anchor or an absent audio stream.
        if (skew !in -200_000_000L..200_000_000L) {
            rejectedOffsets++
            return null
        }
        return nowNs + skew
    }
    @Synchronized fun videoWasSkipped(ptsUs: Long, nowNs: Long): Boolean {
        if (!videoTimestampReliable || audioMediaSample(nowNs) == null) return false
        val audioOrigin = audioOriginNs ?: return false
        val videoOrigin = videoOriginNs ?: return false
        val frame = minOf(written, head + (nowNs - headAtNs).coerceAtLeast(0) * rate / 1_000_000_000L)
        val mediaNs = videoOrigin + ptsUs * 1000 - audioOrigin
        return skips.any { frame >= it.outputBoundary && mediaNs >= sampleTimeNs(it.firstSample) &&
            mediaNs < sampleTimeNs(it.endSample) }
    }
}
