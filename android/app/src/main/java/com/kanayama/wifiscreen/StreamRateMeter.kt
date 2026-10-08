package com.kanayama.wifiscreen

data class StreamCounters(val sessionId: Long, val presentedFrames: Long, val receivedBytes: Long)
data class StreamRates(val framesPerSecond: Double, val bytesPerSecond: Double)

/** Samples cumulative session counters without assuming the UI timer fires exactly on time. */
class StreamRateMeter {
    private var previous: StreamCounters? = null
    private var previousNs = 0L

    fun reset() { previous = null }

    fun sample(counters: StreamCounters, nowNs: Long): StreamRates? {
        val before = previous
        val elapsedNs = nowNs - previousNs
        previous = counters
        previousNs = nowNs
        if (before == null || before.sessionId != counters.sessionId || elapsedNs <= 0 ||
            counters.presentedFrames < before.presentedFrames || counters.receivedBytes < before.receivedBytes) return null
        val seconds = elapsedNs / 1_000_000_000.0
        return StreamRates((counters.presentedFrames - before.presentedFrames) / seconds,
            (counters.receivedBytes - before.receivedBytes) / seconds)
    }
}
