package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test

class AudioOutputClockTest {
    private val rate = 48000
    private fun read(clock: AudioOutputClock, tick: Int, valid: Boolean = true): AudioOutputClock.Reading {
        val now = 1_000_000_000L + tick * 20_000_000L
        val head = 48000L + tick * 960L
        return clock.update(head, head + 4800, head - 8160,
            now - if (valid) 20_000_000L else 200_000_000L, now, now, rate, true)
    }
    @Test fun hardwareAndHeadAreComparedAtTheSameInstant() {
        assertTrue(AudioTimestampQuality.usable(48000, 37440, 60000,
            940_000_000, 1_000_000_000, 1_000_000_000, rate))
        assertFalse(AudioTimestampQuality.usable(48000, 34000, 60000,
            940_000_000, 1_000_000_000, 1_000_000_000, rate))
    }
    @Test fun intermittentTimestampsDoNotRepeatedlySwitchTheOutputClock() {
        val clock = AudioOutputClock()
        repeat(300) { i -> assertFalse(read(clock, i, i % 2 == 0).hardware) }
        assertEquals(0L, clock.switches)
        assertTrue(read(clock, 300, false).stable)
    }
    @Test fun acquisitionCorrectsPhaseWithoutJumpingBack150Milliseconds() {
        val clock = AudioOutputClock()
        var previous = read(clock, 0)
        for (tick in 1..200) {
            val next = read(clock, tick)
            assertTrue(next.frames - previous.frames in 863L..1057L)
            if (tick == 15) { assertTrue(next.hardware); assertFalse(next.stable) }
            previous = next
        }
        assertEquals(1L, clock.switches)
        assertTrue(previous.stable)
        assertEquals(48000L + 200 * 960L - 7200L, previous.frames)
    }
    @Test fun fallbackRetainsSpeakerOffsetAndRecoversWithoutAJump() {
        val clock = AudioOutputClock()
        var previous = read(clock, 0)
        for (tick in 1..220) {
            val next = read(clock, tick, tick !in 160..180)
            assertTrue(next.frames - previous.frames in 863L..1057L)
            if (tick == 160) { assertFalse(next.hardware); assertFalse(next.stable) }
            previous = next
        }
        assertEquals(3L, clock.switches)
        assertTrue(previous.stable)
        assertEquals(48000L + 220 * 960L - 7200L, previous.frames)
    }
    @Test fun stalledPlaybackAndDelayedSamplingSuspendSynchronization() {
        val clock = AudioOutputClock()
        for (i in 0..30) read(clock, i, false)
        var reading = clock.update(90000, 95000, -1, 0, 0, 2_000_000_000, rate, true)
        assertFalse(reading.stable)
        for (i in 1..20) reading = clock.update(90000, 95000, -1, 0, 0,
            2_000_000_000 + i * 20_000_000L, rate, true)
        assertFalse(reading.stable)
        assertEquals(90000L, reading.frames)
    }
    @Test fun emptyTrackAndRestartCannotInventPlayedSamples() {
        val clock = AudioOutputClock()
        for (i in 0..20) {
            val reading = clock.update(480, 480, -1, 0, 0, 1_000_000_000 + i * 20_000_000L, rate, true)
            assertTrue(reading.frames <= 480)
        }
        clock.reset()
        val restarted = clock.update(0, 480, -1, 0, 0, 3_000_000_000, rate, true)
        assertEquals(0L, restarted.frames)
        assertFalse(restarted.stable)
        assertEquals(0L, clock.switches)
    }
}
