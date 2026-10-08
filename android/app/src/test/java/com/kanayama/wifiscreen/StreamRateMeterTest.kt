package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test

class StreamRateMeterTest {
    @Test fun delayedRefreshUsesActualElapsedTimeAndIdleStreamReturnsZero() {
        val meter = StreamRateMeter()
        assertNull(meter.sample(StreamCounters(1, 100, 5_000_000), 1_000_000_000))
        val rates = meter.sample(StreamCounters(1, 145, 8_000_000), 2_500_000_000)!!
        assertEquals(30.0, rates.framesPerSecond, 0.001)
        assertEquals(2_000_000.0, rates.bytesPerSecond, 0.001)
        val idle = meter.sample(StreamCounters(1, 145, 8_000_000), 3_500_000_000)!!
        assertEquals(0.0, idle.framesPerSecond, 0.0)
        assertEquals(0.0, idle.bytesPerSecond, 0.0)
    }

    @Test fun reconnectAndReenableDiscardThePreviousSamplingWindow() {
        val meter = StreamRateMeter()
        meter.sample(StreamCounters(1, 50, 2_000_000), 1_000_000_000)
        assertNull(meter.sample(StreamCounters(2, 1, 100_000), 2_000_000_000))
        assertEquals(30.0, meter.sample(StreamCounters(2, 31, 1_100_000), 3_000_000_000)!!.framesPerSecond, 0.001)
        meter.reset()
        assertNull(meter.sample(StreamCounters(2, 331, 11_100_000), 13_000_000_000))
    }

    @Test fun invalidCounterOrClockIntervalsNeverProduceNegativeOrInfiniteRates() {
        val meter = StreamRateMeter()
        meter.sample(StreamCounters(1, 10, 1000), 1_000_000_000)
        assertNull(meter.sample(StreamCounters(1, 11, 1100), 1_000_000_000))
        assertNull(meter.sample(StreamCounters(1, 0, 0), 2_000_000_000))
        assertEquals(30.0, meter.sample(StreamCounters(1, 30, 1000), 3_000_000_000)!!.framesPerSecond, 0.001)
    }
}
