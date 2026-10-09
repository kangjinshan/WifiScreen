package com.kanayama.wifiscreen

import kotlin.math.abs

/** Selects output progress without stepping between mixer and speaker timelines. */
class AudioOutputClock {
    data class Reading(val frames: Long, val hardware: Boolean, val stable: Boolean, val correctionMs: Long)
    private var goodSinceNs: Long? = null
    private var sourceChangedNs = 0L
    private var hardware = false
    private var headOffset = 0.0
    private var position: Double? = null
    private var sampledAtNs = 0L
    private var previousRaw = -1.0
    private var progressedAtNs = 0L
    var switches = 0L
        private set

    fun reset() {
        goodSinceNs = null; sourceChangedNs = 0; hardware = false; headOffset = 0.0
        position = null; sampledAtNs = 0; previousRaw = -1.0; progressedAtNs = 0; switches = 0
    }

    fun update(head: Long, written: Long, hardwareFrame: Long, hardwareAtNs: Long,
        hardwareProgressAtNs: Long, nowNs: Long, rate: Int, playing: Boolean): Reading {
        if (!playing) {
            reset()
            return Reading(head.coerceIn(0, written), false, false, 0)
        }
        val usable = AudioTimestampQuality.usable(head, hardwareFrame, written,
            hardwareAtNs, hardwareProgressAtNs, nowNs, rate)
        if (usable) {
            if (goodSinceNs == null) goodSinceNs = nowNs
        } else goodSinceNs = null
        val selectHardware = usable && (hardware || nowNs - goodSinceNs!! >= 300_000_000L)
        if (selectHardware != hardware) {
            hardware = selectHardware; sourceChangedNs = nowNs; switches++
        }
        val raw = if (hardware) {
            val projected = (hardwareFrame + (nowNs - hardwareAtNs) * rate / 1_000_000_000.0)
                .coerceIn(0.0, written.toDouble())
            // Preserve the measured output-path offset when timestamps disappear.
            headOffset = head - projected
            projected
        } else (head - headOffset).coerceIn(0.0, written.toDouble())

        if (raw != previousRaw) { previousRaw = raw; progressedAtNs = nowNs }
        val previous = position
        val elapsedNs = (nowNs - sampledAtNs).coerceAtLeast(0)
        val advanced = elapsedNs * rate / 1_000_000_000.0
        val predicted = ((previous ?: raw) + if (previous == null) 0.0 else advanced)
            .coerceAtMost(written.toDouble())
        val error = raw - predicted
        val stalled = nowNs - progressedAtNs > 100_000_000L
        val discontinuity = elapsedNs > 100_000_000L && previous != null
        // Correct by at most 10% of elapsed media time. Never alter the PCM itself.
        val next = if (previous == null || stalled || discontinuity) raw else
            (predicted + error.coerceIn(-advanced / 10, advanced / 10)).coerceIn(0.0, written.toDouble())
        if (previous == null || stalled || discontinuity) sourceChangedNs = nowNs
        position = next; sampledAtNs = nowNs
        val stable = !stalled && nowNs - sourceChangedNs >= 300_000_000L && abs(raw - next) <= rate / 50.0
        return Reading(next.toLong(), hardware, stable, ((raw - next) * 1000 / rate).toLong())
    }
}
