package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioContinuityTest {
    private fun tone(start: Int, frames: Int): ByteArray = ByteBuffer.allocate(frames * 4)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) { i ->
                val sample = (12000 * sin(2 * PI * 350 * (start + i) / 44100)).toInt().toShort()
                putShort(sample); putShort((-sample.toInt()).toShort())
            }
        }.array()
    private fun energy(pcm: ByteArray): Double {
        val b = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        var total = 0.0
        while (b.hasRemaining()) { val v = b.short.toDouble(); total += v * v }
        return total / (pcm.size / 2)
    }
    @Test fun healthyPcmKeepsItsContentAndDuration() {
        val audio = AudioContinuity(44100, 2)
        repeat(100) { i ->
            val pcm = tone(i * 480, 480)
            val blocks = audio.accept(i * 480L, pcm, i * 480L * 1_000_000_000 / 44100)
            assertEquals(1, blocks.size)
            assertArrayEquals(pcm, blocks.single().data)
        }
        assertEquals(0, audio.concealedSamples)
        assertEquals(0, audio.skippedSamples)
        assertEquals(0, audio.overlapSamples)
    }
    @Test fun lostPacketContinuesWaveformInsteadOfAddingFlatSilence() {
        val audio = AudioContinuity(44100, 2)
        audio.accept(0, tone(0, 960), 0)
        val blocks = audio.accept(1440, tone(1440, 480), 33_000_000)
        assertEquals(listOf(960L, 1440L), blocks.map { it.mediaSample })
        val repair = blocks.first().data
        assertEquals(1920, repair.size)
        assertTrue(energy(repair) > energy(tone(960, 480)) * 0.5)
        val b = ByteBuffer.wrap(repair).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 480) assertTrue(abs(b.getShort(i * 4) + b.getShort(i * 4 + 2)) <= 1)
        assertEquals(480, audio.concealedSamples)
        assertEquals(0, audio.skippedSamples)
    }
    @Test fun recoveryCrossfadeKeepsTheTailAndSoftensTheJoin() {
        val wave = PcmConcealer(44100, 2)
        wave.remember(tone(0, 1764))
        val repair = wave.conceal(480)
        val recovered = tone(4000, 480)
        val original = recovered.copyOf()
        wave.join(recovered)
        val a = ByteBuffer.wrap(repair).order(ByteOrder.LITTLE_ENDIAN).getShort(repair.size - 4)
        val b = ByteBuffer.wrap(recovered).order(ByteOrder.LITTLE_ENDIAN).getShort(0)
        assertTrue(abs(a - b) < 1500)
        assertArrayEquals(original.copyOfRange(600, original.size), recovered.copyOfRange(600, recovered.size))
    }
    @Test fun exhaustedRepairBudgetEndsSmoothlyWhenNoAudioReturns() {
        val wave = PcmConcealer(44100, 2)
        wave.remember(tone(0, 1764))
        val repaired = wave.conceal(882)
        val tail = ByteBuffer.wrap(repaired).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0, tail.getShort(repaired.size - 2).toInt())
        assertEquals(0, tail.getShort(repaired.size - 4).toInt())
        assertTrue(energy(repaired.copyOfRange(0, 480 * 4)) > 1000000)
    }
    @Test fun longGapJoinsNextAudioWithoutResettingOrWritingItsMissingDuration() {
        val audio = AudioContinuity(44100, 2)
        audio.accept(0, tone(0, 960), 0)
        val blocks = audio.accept(5760, tone(5760, 480), 130_000_000)
        assertEquals(1, blocks.size)
        assertEquals(5760L, blocks.single().mediaSample)
        assertEquals(1920, blocks.single().data.size)
        assertEquals(4800, audio.skippedSamples)
        assertEquals(0, audio.concealedSamples)
    }
    @Test fun starvationRepairIsBoundedAndLatePcmCannotReplayThoseSamples() {
        val audio = AudioContinuity(44100, 2)
        audio.accept(0, tone(0, 960), 0)
        assertNull(audio.protectBuffer(0, 1200, 10_000_000, 0))
        assertNull(audio.protectBuffer(2000, 1200, 16_000_000, 0))
        val repairs = (0..3).map { audio.protectBuffer(100, 1200, 16_000_000L + it * 2_000_000L, 0)!! }
        assertTrue(repairs.all { it.data.size <= 221 * 4 })
        assertEquals(882 * 4, repairs.sumOf { it.data.size })
        assertNull(audio.protectBuffer(0, 1200, 40_000_000, 0))
        assertTrue(audio.accept(960, tone(960, 480), 41_000_000).isEmpty())
        val recovered = audio.accept(1440, tone(1440, 480), 42_000_000).single()
        assertEquals(1842L, recovered.mediaSample)
        assertEquals(78 * 4, recovered.data.size)
        assertEquals(882, audio.overlapSamples)
        assertEquals(882, audio.starvationSamples)
    }
    @Test fun silentInputDoesNotInventAHighEnergyTone() {
        val audio = AudioContinuity(44100, 2)
        audio.accept(0, ByteArray(960 * 4), 0)
        val repaired = audio.accept(1440, ByteArray(480 * 4), 33_000_000).first().data
        assertTrue(repaired.all { it == 0.toByte() })
    }
    @Test fun noHistoryDoesNotCreateArtificialPreroll() {
        val audio = AudioContinuity(44100, 1)
        assertNull(audio.protectBuffer(0, 1000, 1_000_000_000, 0))
        assertEquals(1, audio.accept(4800, ByteArray(480 * 2), 1_000_000_000).size)
        assertEquals(0, audio.concealedSamples)
    }
    @Test fun batchedDecoderOutputWithFreshPacketsIsNotAnAudioLoss() {
        val audio = AudioContinuity(44100, 2)
        audio.accept(0, tone(0, 960), 0)
        assertNull(audio.protectBuffer(500, 1200, 20_000_000, 18_000_000))
        assertNull(audio.protectBuffer(500, 1200, 30_000_000, 28_000_000))
        assertEquals(0L, audio.starvationSamples)
    }
    @Test fun almostEmptyPlaybackCanBridgeLateDecoderOutputDespiteFreshRtp() {
        val audio = AudioContinuity(44100, 2)
        audio.accept(0, tone(0, 960), 0)
        val repair = audio.protectBuffer(0, 1200, 20_000_000, 19_000_000)!!
        assertEquals(221 * 4, repair.data.size)
        assertTrue(energy(repair.data) > 1000000)
    }
}
