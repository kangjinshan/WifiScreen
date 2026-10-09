package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test

class VideoSyncPolicyTest {
    @Test fun briefClockCorrectionDoesNotDiscardNormalPictures() {
        val policy = VideoSyncPolicy()
        assertFalse(policy.shouldDrop(-80_000_000, 0, true, false))
        assertFalse(policy.shouldDrop(-80_000_000, 33_000_000, true, false))
        assertFalse(policy.shouldDrop(0, 66_000_000, true, false))
        assertFalse(policy.shouldDrop(-80_000_000, 100_000_000, true, false))
    }
    @Test fun sustainedLatenessNeedsAnAlreadyDecodedReplacement() {
        val policy = VideoSyncPolicy()
        repeat(30) { i -> assertFalse(policy.shouldDrop(-80_000_000, i * 33_333_333L, false, false)) }
        assertTrue(policy.shouldDrop(-80_000_000, 1_000_000_000, true, false))
    }
    @Test fun losingClockConfidenceClearsTheOldLateStreak() {
        val policy = VideoSyncPolicy()
        policy.shouldDrop(-80_000_000, 0, true, false)
        policy.reset()
        assertFalse(policy.shouldDrop(-80_000_000, 500_000_000, true, false))
    }
    @Test fun confirmedAudioSkipStillDropsTheCorrespondingVideoInterval() {
        assertTrue(VideoSyncPolicy().shouldDrop(0, 0, false, true))
    }
}
