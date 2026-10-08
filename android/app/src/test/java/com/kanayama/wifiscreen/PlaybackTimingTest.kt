package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test

class PlaybackTimingTest {
    @Test fun audioAndVideoUseTheSameTwentyMillisecondBudget() {
        assertEquals(3528, PlaybackTiming.audioPrerollBytes(44100, 2))
        assertEquals(20_000_000L, VideoPresentationClock().presentationTime(1234, 0))
    }
    @Test fun videoPacingUsesSourceTimestampInsteadOfArrivalJitter() {
        val clock = VideoPresentationClock()
        assertEquals(20_000_000L, clock.presentationTime(0, 0))
        assertEquals(53_333_000L, clock.presentationTime(33333, 10_000_000))
        assertNull(clock.presentationTime(66666, 200_000_000))
    }
    @Test fun discontinuousTimestampsDoNotCreateUnboundedDelay() {
        val clock = VideoPresentationClock()
        clock.presentationTime(1_000_000, 0)
        assertEquals(70_000_000L, clock.presentationTime(0, 50_000_000))
        assertEquals(100_000_000L, clock.presentationTime(5_000_000, 80_000_000))
    }
    @Test fun codecsWithConstantOutputTimestampsDoNotFreezePresentation() {
        val clock = VideoPresentationClock()
        clock.presentationTime(0, 0)
        assertEquals(120_000_000L, clock.presentationTime(0, 100_000_000))
    }
    @Test fun codecStartupDelayDoesNotBecomePermanentVideoLatency() {
        val clock = VideoPresentationClock()
        clock.observeInput(0, 0)
        assertNull(clock.presentationTime(0, 150_000_000))
        assertEquals(170_000_000L, clock.presentationTime(150_000, 155_000_000))
    }
    @Test fun persistentNetworkDelayCannotFreezeAllFollowingPictures() {
        val clock = VideoPresentationClock()
        clock.observeInput(0, 0)
        assertNull(clock.presentationTime(0, 150_000_000))
        assertNull(clock.presentationTime(33_333, 183_000_000))
        assertEquals(271_000_000L, clock.presentationTime(100_000, 251_000_000))
    }
}
