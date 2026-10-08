package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test

class AvPlaybackClockTest {
    @Test fun stalledOrDistantHardwareTimestampsFallBackToThePlaybackHead() {
        assertTrue(AudioTimestampQuality.usable(48000, 47000, 50000, 950_000_000, 990_000_000, 1_000_000_000, 48000))
        assertFalse(AudioTimestampQuality.usable(48000, 0, 50000, 950_000_000, 990_000_000, 1_000_000_000, 48000))
        assertFalse(AudioTimestampQuality.usable(48000, 47000, 50000, 950_000_000, 800_000_000, 1_000_000_000, 48000))
        assertFalse(AudioTimestampQuality.usable(48000, 60000, 50000, 950_000_000, 990_000_000, 1_000_000_000, 48000))
    }
    @Test fun queuedAudioDoesNotMoveVideoUntilPlaybackStarts() {
        val clock = AvPlaybackClock()
        clock.resetAudio(48000)
        clock.observeAudio(0, 0); clock.observeVideo(1_000_000, 0)
        clock.appendAudio(0, 4800)
        assertNull(clock.videoTargetNs(1_000_000, 100_000_000))
        clock.updateAudio(0, 100_000_000, true)
        assertEquals(100_000_000L, clock.videoTargetNs(1_000_000, 100_000_000))
        assertEquals(120_000_000L, clock.videoTargetNs(1_020_000, 100_000_000))
    }
    @Test fun longGapChangesVideoTimeWhenPlaybackReachesTheJoinNotWhenItIsQueued() {
        val clock = AvPlaybackClock()
        clock.resetAudio(48000); clock.observeAudio(0, 0)
        clock.appendAudio(0, 480); clock.appendAudio(5280, 480)
        clock.updateAudio(0, 100_000_000, true)
        assertEquals(0L, clock.audioMediaSample(100_000_000))
        clock.updateAudio(479, 109_000_000, true)
        assertEquals(479L, clock.audioMediaSample(109_000_000))
        clock.updateAudio(480, 110_000_000, true)
        assertEquals(5280L, clock.audioMediaSample(110_000_000))
    }
    @Test fun correspondingVideoIntervalIsSkippedOnlyAtTheAudibleJoin() {
        val clock = AvPlaybackClock()
        clock.resetAudio(48000); clock.observeAudio(0, 0); clock.observeVideo(0, 0)
        clock.appendAudio(0, 480); clock.appendAudio(5280, 480)
        clock.updateAudio(479, 100_000_000, true)
        assertFalse(clock.videoWasSkipped(20_000, 100_000_000))
        clock.updateAudio(480, 101_000_000, true)
        assertTrue(clock.videoWasSkipped(20_000, 101_000_000))
        assertTrue(clock.videoWasSkipped(100_000, 101_000_000))
        assertFalse(clock.videoWasSkipped(110_000, 101_000_000))
        assertFalse(clock.videoWasSkipped(5000, 101_000_000))
    }
    @Test fun hardwareTimestampCanTrailTheClientHeadWithoutLosingTheGapMapping() {
        val clock = AvPlaybackClock()
        clock.resetAudio(48000); clock.observeAudio(0, 0)
        clock.appendAudio(0, 480); clock.appendAudio(5280, 480)
        clock.updateAudio(700, 120_000_000, true)
        clock.updateAudio(400, 110_000_000, true)
        assertEquals(400L, clock.audioMediaSample(110_000_000))
        clock.updateAudio(480, 130_000_000, true)
        assertEquals(5280L, clock.audioMediaSample(130_000_000))
    }
    @Test fun starvingClockCannotExtrapolatePastWrittenPcmOrFreezeVideoForever() {
        val clock = AvPlaybackClock()
        clock.resetAudio(48000); clock.observeAudio(0, 0)
        clock.appendAudio(0, 480)
        clock.updateAudio(0, 100_000_000, true)
        assertEquals(480L, clock.audioMediaSample(150_000_000))
        assertNull(clock.audioMediaSample(201_000_000))
    }
    @Test fun resumingAnEmptyTrackDoesNotPretendNewAudioAlreadyPlayed() {
        val clock = AvPlaybackClock()
        clock.resetAudio(48000); clock.observeAudio(0, 0)
        clock.appendAudio(0, 480); clock.updateAudio(480, 100_000_000, true)
        assertNull(clock.audioMediaSample(210_000_000))
        clock.appendAudio(5280, 480, 220_000_000)
        assertEquals(5280L, clock.audioMediaSample(220_000_000))
        assertEquals(5520L, clock.audioMediaSample(225_000_000))
    }
    @Test fun restartClearsOldAudioAndVideoAnchorsIndependently() {
        val clock = AvPlaybackClock()
        clock.observeVideo(1_000_000, 0); clock.observeAudio(0, 0)
        clock.appendAudio(0, 1000); clock.updateAudio(500, 100_000_000, true)
        clock.resetAudio(44100)
        assertNull(clock.videoTargetNs(1_000_000, 100_000_000))
        clock.observeAudio(0, 100_000_000); clock.appendAudio(0, 1000)
        clock.updateAudio(0, 120_000_000, true)
        assertEquals(120_000_000L, clock.videoTargetNs(1_100_000, 120_000_000))
        clock.resetVideo()
        assertNull(clock.videoTargetNs(1_100_000, 120_000_000))
    }
    @Test fun burstArrivalDoesNotAnchorVideoToTheOldestQueuedPicture() {
        val clock = AvPlaybackClock()
        clock.resetAudio(48000)
        clock.observeVideo(1_000_000, 0); clock.observeVideo(2_000_000, 0)
        clock.observeAudio(0, 0); clock.appendAudio(0, 4800)
        clock.updateAudio(0, 100_000_000, true)
        assertEquals(100_000_000L, clock.videoTargetNs(2_000_000, 100_000_000))
        assertTrue(clock.videoTargetNs(1_000_000, 100_000_000)!! < 0)
        assertNull(clock.videoTargetNs(3_000_000, 100_000_000))
    }
    @Test fun longSessionsDoNotOverflowTheSampleToNanosecondConversion() {
        val clock = AvPlaybackClock()
        clock.resetAudio(48000)
        val sample = 48000L * 86400 * 7
        clock.observeAudio(sample, 1_000_000_000)
        clock.observeVideo(1_000_000, 1_000_000_000)
        clock.appendAudio(sample, 480)
        clock.updateAudio(0, 1_100_000_000, true)
        assertEquals(1_100_000_000L, clock.videoTargetNs(1_000_000, 1_100_000_000))
    }
    @Test fun constantVideoTimestampsUseIndependentPacingInsteadOfDroppingForever() {
        val clock = AvPlaybackClock()
        clock.observeAudio(0, 0); clock.appendAudio(0, 4410)
        clock.updateAudio(441, 100_000_000, true)
        clock.observeVideo(0, 0); clock.observeVideo(0, 33_000_000)
        assertNull(clock.videoTargetNs(0, 100_000_000))
        clock.observeVideo(33_000, 66_000_000)
        assertNotNull(clock.videoTargetNs(33_000, 100_000_000))
    }
}
