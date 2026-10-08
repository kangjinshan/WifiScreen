package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test

class AudioRtpQueueTest {
    private fun packet(sequence: Int) = AudioRtpFrame(sequence, sequence * 480L, byteArrayOf(1))
    @Test fun reorderedPacketsAreRecoveredWithinBudgetWithoutDuplicates() {
        val queue = AudioRtpQueue()
        queue.offer(packet(10), 0); assertEquals(10, queue.poll(0)?.sequence)
        queue.offer(packet(12), 1_000_000); assertNull(queue.poll(1_000_000))
        queue.offer(packet(11), 9_000_000); queue.offer(packet(11), 10_000_000)
        assertEquals(11, queue.poll(10_000_000)?.sequence)
        assertEquals(12, queue.poll(10_000_000)?.sequence)
        assertEquals(0L, queue.missingPackets)
        assertEquals(1L, queue.reorderedPackets)
        assertEquals(1L, queue.duplicatePackets)
    }
    @Test fun missingPacketDeadlineIsBoundedAndLatePacketsCannotReplay() {
        val queue = AudioRtpQueue()
        queue.offer(packet(10), 0); queue.poll(0)
        queue.offer(packet(12), 22_000_000); assertNull(queue.poll(22_000_000))
        assertNull(queue.poll(30_000_000))
        assertEquals(12, queue.poll(31_000_000)?.sequence)
        queue.offer(packet(11), 32_000_000)
        assertEquals(1L, queue.missingPackets)
        assertEquals(1L, queue.latePackets)
        assertNull(queue.poll(33_000_000))
    }
    @Test fun sequenceWrapKeepsOrderAndCloseClearsQueue() {
        val queue = AudioRtpQueue()
        queue.offer(packet(65535), 0); queue.offer(packet(0), 10_000_000)
        assertEquals(65535, queue.poll(10_000_000)?.sequence)
        assertEquals(0, queue.poll(10_000_000)?.sequence)
        queue.close(); queue.offer(packet(1), 20_000_000)
        assertEquals(0, queue.size)
    }
    @Test fun newRtpSourceCanRestartItsSequence() {
        val queue = AudioRtpQueue()
        queue.offer(AudioRtpFrame(5000, 9000, byteArrayOf(1), ssrc = 1), 0)
        assertEquals(5000, queue.poll(0)?.sequence)
        queue.offer(AudioRtpFrame(0, 0, byteArrayOf(2), ssrc = 2), 1_000_000)
        assertEquals(0, queue.poll(1_000_000)?.sequence)
        assertEquals(0L, queue.latePackets)
    }
}
