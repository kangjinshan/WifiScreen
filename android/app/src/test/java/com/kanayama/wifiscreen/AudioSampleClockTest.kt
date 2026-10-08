package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioSampleClockTest {
    @Test fun timestampWrapAndMissingPacketsKeepMediaTime() {
        val clock = AudioSampleClock()
        assertEquals(0L, clock.position(65535, 0xfffffff0L))
        assertEquals(480L, clock.position(0, 464))
        assertEquals(1440L, clock.position(2, 1424))
    }
    @Test fun constantSenderTimestampsUseExactSampleCountWithoutRoundingDrift() {
        val clock = AudioSampleClock()
        clock.position(0, 0)
        var samples = 0L
        for (i in 1..60000) samples = clock.position(i and 65535, 0)
        assertEquals(28_800_000L, samples)
    }
    @Test fun nonSampleTimestampUnitsDoNotInterruptDecodedAudio() {
        // Legacy senders may put milliseconds, microseconds or a fractional clock
        // in this field. Each negotiated AAC-ELD access unit still has 480 samples.
        for (ticksPerPacket in listOf(10L, 10_884L, 46_747_943L, 0x10000000L)) {
            val clock = AudioSampleClock()
            for (i in 0..2000) {
                assertEquals("timestamp step $ticksPerPacket at packet $i", i * 480L,
                    clock.position(i, i * ticksPerPacket and 0xffffffffL))
            }
        }
    }
    @Test fun byteSwappedTimestampsAndClockJumpsKeepContinuousSamples() {
        val clock = AudioSampleClock()
        for (i in 0..2000) {
            val timestamp = when {
                i < 500 -> i * 480L
                i < 1000 -> Integer.reverseBytes(i * 480).toLong() and 0xffffffffL
                i < 1500 -> 123L
                else -> (i - 1500) * 480L
            }
            assertEquals(i * 480L, clock.position(i, timestamp))
        }
    }
    @Test fun invalidTimestampsStillPreserveLostPacketTimeAcrossSequenceWrap() {
        val clock = AudioSampleClock()
        assertEquals(0L, clock.position(65534, 1000))
        assertEquals(480L, clock.position(65535, 11000))
        assertEquals(1920L, clock.position(2, 41000))
        assertEquals(2400L, clock.position(3, 51000))
        clock.reset()
        assertEquals(0L, clock.position(123, 900000))
        assertEquals(480L, clock.position(124, 910000))
    }
    @Test fun shortConcealmentHasCorrectDurationAndFadesToSilence() {
        val pcm = PcmGap.silenceWithFade(480, shortArrayOf(12000, -12000))
        assertEquals(1920, pcm.size)
        val b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        assertTrue(b.getShort(0) in 1..12000)
        assertEquals(0, b.getShort(252).toInt())
        assertEquals(0, b.getShort(1918).toInt())
        PcmGap.fadeIn(pcm, 2)
        assertEquals(0, b.getShort(0).toInt())
    }
}
