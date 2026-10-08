package com.kanayama.wifiscreen

/** The same small added delay is used for audio preroll and video presentation. */
object PlaybackTiming {
    const val BUFFER_MS = 20
    const val BUFFER_NS = BUFFER_MS * 1_000_000L
    fun audioPrerollBytes(rate: Int, channels: Int): Int =
        ((rate.toLong() * BUFFER_MS + 999) / 1000).toInt() * channels * 2
}

class VideoPresentationClock {
    private var firstPtsUs: Long? = null
    private var baseNs = 0L
    private var previousPtsUs: Long? = null
    private var lateSinceNs: Long? = null

    fun reset() { firstPtsUs = null; previousPtsUs = null; lateSinceNs = null }

    fun observeInput(ptsUs: Long, receivedNs: Long) {
        if (firstPtsUs == null) { firstPtsUs = ptsUs; baseNs = receivedNs + PlaybackTiming.BUFFER_NS }
    }

    /** Null means skip presentation of a late picture, never skip its decoding/reference data. */
    fun presentationTime(ptsUs: Long, nowNs: Long): Long? {
        val first = firstPtsUs
        if (first == null || previousPtsUs?.let { ptsUs <= it } == true) {
            firstPtsUs = ptsUs
            baseNs = nowNs + PlaybackTiming.BUFFER_NS
            lateSinceNs = null
        }
        previousPtsUs = ptsUs
        var target = baseNs + (ptsUs - firstPtsUs!!) * 1000
        if (target > nowNs + 250_000_000L) {
            // Sender clock/resolution restart: do not accumulate seconds of delay.
            firstPtsUs = ptsUs
            baseNs = nowNs + PlaybackTiming.BUFFER_NS
            target = baseNs
            lateSinceNs = null
        }
        if (target < nowNs - 60_000_000L) {
            val since = lateSinceNs ?: nowNs.also { lateSinceNs = it }
            if (nowNs - since < 100_000_000L) return null
            // A persistent network clock shift must not turn presentation dropping
            // into a permanently frozen picture. Re-anchor after a short catch-up window.
            firstPtsUs = ptsUs; baseNs = nowNs + PlaybackTiming.BUFFER_NS
            lateSinceNs = null
            return baseNs
        }
        lateSinceNs = null
        return maxOf(target, nowNs)
    }
}
