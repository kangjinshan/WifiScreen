package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test

class CodecRecommendationTest {
    private val avc = DecodeTrial(VideoEncoding.H264, "avc", true, 500, p95Ms = 15)
    private val hevc = DecodeTrial(VideoEncoding.H265, "hevc", true, 300, p95Ms = 25)
    private val network = NetworkTrial(true, transport = "Wi-Fi", linkMbps = 200, rssi = -50, probes = 20, replies = 20, p95Ms = 3)
    @Test fun prefersHevcWhenDecodeHasHeadroomAndLanIsConstrained() {
        assertEquals(VideoEncoding.H265, CodecRecommendation.evaluate(avc, hevc, network.copy(rssi = -80)).recommended)
        assertEquals(VideoEncoding.H265, CodecRecommendation.evaluate(avc, hevc, network.copy(p95Ms = 70)).recommended)
    }
    @Test fun neverTradesAwayDecodingForBandwidth() {
        assertEquals(VideoEncoding.H264, CodecRecommendation.evaluate(avc, hevc.copy(frames = 100), network.copy(rssi = -80)).recommended)
        assertEquals(VideoEncoding.H264, CodecRecommendation.evaluate(avc, hevc.copy(error = "unavailable"), network.copy(rssi = -80)).recommended)
        assertEquals(VideoEncoding.H264, CodecRecommendation.evaluate(avc, hevc.copy(p95Ms = 150), network).recommended)
        assertEquals(VideoEncoding.H264, CodecRecommendation.evaluate(avc, hevc.copy(hardware = false), network).recommended)
    }
    @Test fun returnsNoRecommendationWhenBothFailOrNetworkChanges() {
        assertNull(CodecRecommendation.evaluate(avc.copy(frames = 0), hevc.copy(frames = 0), network).recommended)
        assertNull(CodecRecommendation.evaluate(avc, hevc, network.copy(connected = false)).recommended)
        assertNull(CodecRecommendation.evaluate(avc, hevc, network.copy(changed = true)).recommended)
    }
    @Test fun unknownNetworkAndBlockedPingAreNotReportedAsCongestion() {
        val unknown = NetworkTrial(true, probes = 20)
        assertFalse(unknown.constrained)
        assertFalse(unknown.observed)
        assertEquals(VideoEncoding.H264, CodecRecommendation.evaluate(avc, hevc, unknown).recommended)
        assertEquals(VideoEncoding.H264, CodecRecommendation.evaluate(avc, hevc, network).recommended)
    }
    @Test fun selectsOnlyPassingCodecAndKeepsInvalidPreferencesSafe() {
        assertEquals(VideoEncoding.H265, CodecRecommendation.evaluate(avc.copy(error = "failed"), hevc, network).recommended)
        assertEquals(VideoEncoding.H264, VideoEncoding.saved(null))
        assertEquals(VideoEncoding.H264, VideoEncoding.saved("unexpected"))
        assertEquals(VideoEncoding.H265, VideoEncoding.saved("H265"))
    }
}
