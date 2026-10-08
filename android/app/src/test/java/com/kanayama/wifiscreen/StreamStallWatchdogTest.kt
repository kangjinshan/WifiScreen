package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test

class StreamStallWatchdogTest {
    @Test fun staticScreenIsNeverDisconnected() {
        val watch = StreamStallWatchdog()
        for (i in 1..100) assertFalse(watch.check(i * 2000L, 1, 30, 30, 0, i * 2000L, true))
    }
    @Test fun sustainedQueuedMediaWithoutOutputIsDetected() {
        val watch = StreamStallWatchdog()
        assertFalse(watch.check(1000, 1, 30, 20, 10, 1000, true))
        assertFalse(watch.check(2000, 1, 40, 20, 20, 2000, true))
        assertTrue(watch.check(14_000, 1, 50, 20, 30, 14_000, true))
    }
    @Test fun outputAndNewSessionResetGracePeriod() {
        val watch = StreamStallWatchdog()
        watch.check(1000, 1, 30, 20, 10, 1000, true)
        watch.check(2000, 1, 40, 20, 20, 2000, true)
        assertFalse(watch.check(14_000, 1, 40, 21, 19, 14_000, true))
        assertFalse(watch.check(16_000, 2, 1, 0, 1, 100, false))
    }
    @Test fun connectedButNeverProducingFirstPictureTimesOut() {
        val watch = StreamStallWatchdog()
        watch.check(1000, 1, 0, 0, 0, 0, false)
        assertTrue(watch.check(28_000, 1, 0, 0, 0, 27_000, false))
    }
}
