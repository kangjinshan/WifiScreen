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
