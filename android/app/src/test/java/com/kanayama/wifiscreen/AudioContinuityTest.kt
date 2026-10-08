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
            val original = pcm.copyOf()
            val blocks = audio.accept(i * 480L, pcm)
            assertEquals(1, blocks.size)
            assertArrayEquals(original, blocks.single().data)
            assertArrayEquals(original, pcm)
        }
        assertEquals(0, audio.concealedSamples)
        assertEquals(0, audio.skippedSamples)
        assertEquals(0, audio.overlapSamples)
    }
    @Test fun lostPacketContinuesWaveformInsteadOfAddingFlatSilence() {
        val audio = AudioContinuity(44100, 2)
        audio.accept(0, tone(0, 960))
        val blocks = audio.accept(1440, tone(1440, 480))
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
        audio.accept(0, tone(0, 960))
        val blocks = audio.accept(5760, tone(5760, 480))
        assertEquals(1, blocks.size)
        assertEquals(5760L, blocks.single().mediaSample)
        assertEquals(1920, blocks.single().data.size)
        assertEquals(4800, audio.skippedSamples)
        assertEquals(0, audio.concealedSamples)
    }
    @Test fun duplicateDecodedAudioCannotPlayTwice() {
        val audio = AudioContinuity(44100, 2)
        audio.accept(0, tone(0, 480))
        assertTrue(audio.accept(0, tone(0, 480)).isEmpty())
        val next = tone(480, 480)
        assertArrayEquals(next, audio.accept(480, next.copyOf()).single().data)
        assertEquals(480L, audio.overlapSamples)
        assertEquals(0L, audio.concealedSamples)
    }
    @Test fun silentInputDoesNotInventAHighEnergyTone() {
        val audio = AudioContinuity(44100, 2)
        audio.accept(0, ByteArray(960 * 4))
        val repaired = audio.accept(1440, ByteArray(480 * 4)).first().data
        assertTrue(repaired.all { it == 0.toByte() })
    }
    @Test fun noHistoryDoesNotCreateArtificialPreroll() {
        val audio = AudioContinuity(44100, 1)
        assertEquals(1, audio.accept(4800, ByteArray(480 * 2)).size)
        assertEquals(0, audio.concealedSamples)
    }
    @Test fun completeBatchesNeverInsertSyntheticAudioOrDiscardRealSamples() {
        val audio = AudioContinuity(44100, 2)
        var position = 0
        repeat(200) { i ->
            val count = if (i % 3 == 0) 960 else 480
            val pcm = tone(position, count)
            val blocks = audio.accept(position.toLong(), pcm.copyOf())
            assertEquals(1, blocks.size)
            assertArrayEquals(pcm, blocks.single().data)
            position += count
        }
        assertEquals(0L, audio.concealedSamples)
        assertEquals(0L, audio.overlapSamples)
        assertEquals(0L, audio.skippedSamples)
    }
}
