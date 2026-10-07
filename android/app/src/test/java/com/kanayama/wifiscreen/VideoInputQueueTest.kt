package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VideoInputQueueTest {
    @Test fun slowConsumerKeepsConfigurationKeyframeAndDependentPicturesInOrder() {
        val queue = VideoInputQueue<String>(2)
        assertTrue(queue.put("configuration"))
        assertTrue(queue.put("keyframe"))
        val entered = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val producer = Thread {
            entered.countDown()
            queue.put("picture1")
            queue.put("picture2")
            finished.countDown()
        }.apply { start() }
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertFalse(finished.await(150, TimeUnit.MILLISECONDS))
            val actual = mutableListOf<String>()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (actual.size < 4 && System.nanoTime() < deadline) queue.poll()?.let { actual.add(it) }
            assertEquals(listOf("configuration", "keyframe", "picture1", "picture2"), actual)
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertTrue(queue.waits.get() > 0)
        } finally { queue.close(); producer.join(1000) }
    }

    @Test fun closeUnblocksProducerWhenDecoderStopsWithFullQueue() {
        val queue = VideoInputQueue<Int>(1)
        assertTrue(queue.put(1))
        val accepted = AtomicBoolean(true)
        val producer = Thread { accepted.set(queue.put(2)) }.apply { start() }
        queue.close()
        producer.join(1000)
        assertFalse(producer.isAlive)
        assertFalse(accepted.get())
        assertFalse(queue.put(3))
    }
}
