package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test

class VideoDiagnosticsTest {
    @Test fun boundedHistoryCannotMutateAnEarlierSnapshot() {
        val d = VideoDiagnostics(100)
        repeat(40) { d.event(100L + it, "test", "event-$it") }
        val before = d.snapshot(200)
        assertEquals(32, before.events.size)
        assertEquals("event-8", before.events.first().detail)
        repeat(40) { d.event(300L + it, "new", "replacement") }
        assertEquals("event-8", before.events.first().detail)
        assertEquals(32, d.snapshot(400).events.size)
    }
    @Test fun tracksLongDecoderAgeKeyframeAgeAndRebuildReason() {
        val d = VideoDiagnostics(100)
        d.started(200, "hardware"); d.keyframe(300); d.idr(400)
        val before = d.snapshot(7_200_500)
        assertEquals(7_200_300L, before.decoderAgeMs)
        assertEquals(7_200_200L, before.keyframeAgeMs)
        assertEquals(7_200_100L, before.idrAgeMs)
        d.released(7_200_600, "用户修复画面"); d.started(7_200_700, "hardware")
        val after = d.snapshot(7_200_800)
        assertEquals(100L, after.decoderAgeMs)
        assertEquals(2L, after.decoderStarts); assertEquals(1L, after.decoderReleases)
        assertEquals("用户修复画面", after.lastResetReason)
        assertEquals(7_200_300L, before.decoderAgeMs)
    }
    @Test fun repeatedConfigurationDoesNotFabricateChangesOrKnownColors() {
        val d = VideoDiagnostics(0)
        val p = AvcConfiguration(byteArrayOf(1, 2, 3), byteArrayOf(4, 5, 6))
        d.configuration(1, p, null, true)
        val first = d.snapshot(2)
        assertEquals(64, first.configurationHash.length)
        d.configuration(3, p, null, false)
        assertEquals(first.configurationHash, d.snapshot(4).configurationHash)
        assertEquals(1L, d.snapshot(4).configurationVersion)
        assertNull(d.snapshot(4).source)
        assertNull(d.snapshot(4).output)
    }
}
