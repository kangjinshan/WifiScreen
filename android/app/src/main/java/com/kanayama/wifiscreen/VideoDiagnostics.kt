package com.kanayama.wifiscreen

import java.security.MessageDigest
import java.util.ArrayDeque

data class VideoFormat(val width: Int?, val height: Int?, val colorFormat: Int?,
    val colorStandard: Int?, val colorTransfer: Int?, val colorRange: Int?, val stride: Int?, val sliceHeight: Int?)
data class VideoDiagnosticEvent(val elapsedMs: Long, val kind: String, val detail: String)
data class VideoDiagnosticSnapshot(val elapsedMs: Long, val decoderAgeMs: Long?, val keyframeAgeMs: Long?,
    val idrAgeMs: Long?, val decoderStarts: Long, val decoderReleases: Long, val lastResetReason: String,
    val configurationVersion: Long, val configurationHash: String, val source: AvcSps?,
    val output: VideoFormat?, val lastInputPtsUs: Long?, val lastOutputPtsUs: Long?, val events: List<VideoDiagnosticEvent>)

/** Metadata only; bounded history and immutable snapshots survive later decoder state changes. */
class VideoDiagnostics(private val sessionStartedMs: Long, private val capacity: Int = 32) {
    init { require(capacity > 0) }
    private val events = ArrayDeque<VideoDiagnosticEvent>()
    private var startedMs: Long? = null
    private var keyframeMs: Long? = null
    private var idrMs: Long? = null
    private var starts = 0L
    private var releases = 0L
    private var resetReason = ""
    private var version = 0L
    private var hash = ""
    private var source: AvcSps? = null
    private var output: VideoFormat? = null
    @Volatile var lastInputPtsUs: Long? = null
    @Volatile var lastOutputPtsUs: Long? = null

    @Synchronized fun event(nowMs: Long, kind: String, detail: String) {
        events.addLast(VideoDiagnosticEvent((nowMs - sessionStartedMs).coerceAtLeast(0), kind.take(48), detail.take(512)))
        while (events.size > capacity) events.removeFirst()
    }
    @Synchronized fun configuration(nowMs: Long, parameters: AvcConfiguration, parsed: AvcSps?, changed: Boolean) {
        if (!changed) return
        version++
        hash = MessageDigest.getInstance("SHA-256").apply { update(parameters.sps); update(parameters.pps) }
            .digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        source = parsed
        event(nowMs, "configuration", "version=$version sha256=$hash source=${parsed ?: "unknown/unsupported"}")
    }
    @Synchronized fun keyframe(nowMs: Long) { keyframeMs = nowMs }
    @Synchronized fun idr(nowMs: Long) { idrMs = nowMs }
    @Synchronized fun started(nowMs: Long, name: String) {
        startedMs = nowMs; starts++; output = null
        event(nowMs, "decoder_started", name)
    }
    @Synchronized fun released(nowMs: Long, reason: String) {
        startedMs = null; releases++; resetReason = reason
        event(nowMs, "decoder_released", reason)
    }
    @Synchronized fun format(nowMs: Long, value: VideoFormat) {
        output = value
        event(nowMs, "output_format", value.toString())
    }
    @Synchronized fun snapshot(nowMs: Long): VideoDiagnosticSnapshot {
        fun age(at: Long?) = at?.let { (nowMs - it).coerceAtLeast(0) }
        return VideoDiagnosticSnapshot((nowMs - sessionStartedMs).coerceAtLeast(0), age(startedMs), age(keyframeMs),
            age(idrMs), starts, releases, resetReason, version, hash, source, output, lastInputPtsUs, lastOutputPtsUs, events.toList())
    }
}
