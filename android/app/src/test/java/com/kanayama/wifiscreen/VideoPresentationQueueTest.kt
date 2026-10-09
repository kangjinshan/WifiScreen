package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test

class VideoPresentationQueueTest {
    private fun frame(index: Int, created: Long, target: Long?) =
        VideoOutputFrame(index, index * 33333L, created, created, 1920, 1080, target, target, true)

    @Test fun waitingForAudioDoesNotPreventDecodingTheNextFrame() {
        val queue = VideoPresentationQueue()
        assertNull(queue.offer(frame(0, 0, 40_000_000)))
        assertNull(queue.poll(33_333_333))
        assertNull(queue.offer(frame(1, 33_333_333, 73_333_333)))
        assertEquals(2, queue.size)
        assertEquals(0, queue.poll(40_000_000)?.index)
        assertEquals(1, queue.poll(73_333_333)?.index)
    }
    @Test fun thirtyFpsCanContinueWhileEachFrameWaitsFortyMilliseconds() {
        val queue = VideoPresentationQueue()
        val released = mutableListOf<Int>()
        repeat(300) { index ->
            val now = index * 1_000_000_000L / 30
            while (true) released.add((queue.poll(now) ?: break).index)
            assertNull(queue.offer(frame(index, now, now + 40_000_000)))
            assertTrue(queue.size <= 2)
        }
        while (true) released.add((queue.poll(11_000_000_000L) ?: break).index)
        assertEquals((0 until 300).toList(), released)
    }
    @Test fun codecBufferPressureReleasesOldestWithoutLosingOwnership() {
        val queue = VideoPresentationQueue(capacity = 2)
        val released = mutableListOf<Int>()
        repeat(20) { index -> queue.offer(frame(index, 0, 150_000_000))?.let { released.add(it.index) } }
        assertEquals(2, queue.size)
        released.addAll(queue.clear().map { it.index })
        assertEquals((0 until 20).toList(), released)
        assertTrue(queue.clear().isEmpty())
    }
    @Test fun defaultQueueCanOverlapNormalAudioDelayWithoutGrowingUnbounded() {
        val queue = VideoPresentationQueue()
        repeat(4) { i -> assertNull(queue.offer(frame(i, i * 33_333_333L, 110_000_000L + i * 33_333_333L))) }
        assertEquals(4, queue.size)
        assertEquals(0, queue.offer(frame(4, 133_333_332, 243_333_332))?.index)
        assertEquals(4, queue.size)
    }
    @Test fun movingAudioDeadlineCannotHoldACodecBufferForever() {
        val queue = VideoPresentationQueue()
        val frame = frame(0, 0, 100_000_000)
        queue.offer(frame)
        frame.targetNs = 1_000_000_000
        assertNull(queue.poll(199_000_000))
        assertEquals(0, queue.poll(200_000_000)?.index)
    }
    @Test fun skippedIntervalsAndExplicitPressureReleaseImmediately() {
        val queue = VideoPresentationQueue()
        val first = frame(0, 0, 100_000_000)
        queue.offer(first); first.targetNs = null; first.syncDrop = true
        assertTrue(queue.poll(1_000_000)!!.syncDrop)
        queue.offer(frame(1, 0, 100_000_000))
        assertEquals(1, queue.poll(2_000_000, force = true)?.index)
    }
}
