package com.kanayama.wifiscreen

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** TCP pictures depend on earlier pictures: backpressure must not discard queued references. */
class VideoInputQueue<T>(capacity: Int) {
    private val queue = ArrayBlockingQueue<T>(capacity)
    @Volatile private var closed = false
    val waits = AtomicLong()
    val size: Int get() = queue.size

    fun put(value: T): Boolean {
        try {
            while (!closed) {
                if (queue.offer(value, 50, TimeUnit.MILLISECONDS)) {
                    if (!closed) return true
                    queue.clear()
                    return false
                }
                waits.incrementAndGet()
            }
        } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        return false
    }

    fun poll(): T? = try { queue.poll(5, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null }
    fun close() { closed = true; queue.clear() }
}
