package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test

class VideoRepairTest {
    private val first = AvcSlice(0, true, 0, 0, 0, 0, 0, 0, 0)
    private val next = first.copy(idr = false, frameNum = 1, idrPicId = null)
    @Test fun collectsAllSlicesBeforeRebuildingAndDoesNotReuseLookahead() {
        val repair = VideoRepair<String>()
        assertTrue(repair.request(100))
        assertNull(repair.offer("old", first, 100, 101, false))
        assertNull(repair.offer("idr-a", first, 100, 110, true))
        assertNull(repair.offer("idr-b", first.copy(firstMb = 30), 100, 120, true))
        assertEquals(VideoRepairState.WAITING, repair.snapshot().state)
        assertEquals(listOf("idr-a", "idr-b"), repair.offer("next", next, 100, 130, true))
        assertEquals(VideoRepairState.REBUILDING, repair.snapshot().state)
        assertNull(repair.offer("duplicate", next, 100, 131, true))
        repair.started(); assertEquals(VideoRepairState.VERIFYING, repair.snapshot().state)
        repair.presented(140); assertEquals(VideoRepairState.PRESENTED, repair.snapshot().state)
        assertEquals(1L, repair.snapshot().attempts)
    }
    @Test fun noKeyframeOrStaticScreenTimesOutWithoutAskingForARebuild() {
        val repair = VideoRepair<Int>()
        repair.request(100)
        repair.offer(1, first, 20, 200, true)
        repair.tick(2099); assertEquals(VideoRepairState.WAITING, repair.snapshot().state)
        repair.tick(2100); assertEquals(VideoRepairState.TIMED_OUT, repair.snapshot().state)
        assertNull(repair.offer(2, next, 20, 2101, true))
        assertFalse(repair.snapshot().active)
    }
    @Test fun repeatedClicksDoNotResetDeadlineAndRetriesHaveCooldown() {
        val repair = VideoRepair<Int>()
        assertTrue(repair.request(100))
        assertFalse(repair.request(2000))
        repair.tick(2100)
        assertFalse(repair.request(30_099))
        assertTrue(repair.request(30_100))
        assertEquals(2L, repair.snapshot().attempts)
    }
    @Test fun configurationChangesAndUnsupportedUnitsInvalidatePartialPictures() {
        val repair = VideoRepair<Int>()
        repair.request(100); repair.offer(1, first, 20, 110, true)
        repair.configurationChanged()
        assertNull(repair.offer(2, next, 20, 120, true))
        repair.offer(3, first, 20, 130, true)
        repair.offer(4, null, 20, 140, true)
        assertNull(repair.offer(5, next, 20, 150, true))
    }
    @Test fun collectorRejectsMissingStartWrongPictureAndOutOfOrderSlices() {
        val c = IdrPictureCollector<Int>()
        c.offer(1, first.copy(firstMb = 30), 20)
        assertNull(c.offer(2, next, 20))
        c.offer(3, first, 20); c.offer(4, first.copy(firstMb = 30, idrPicId = 2), 20)
        assertNull(c.offer(5, next, 20))
        c.offer(6, first, 20); c.offer(7, first.copy(firstMb = 30), 20)
        c.offer(8, first.copy(firstMb = 20), 20)
        assertNull(c.offer(9, next, 20))
    }
    @Test fun collectorBoundsRetainedMediaByBytesAndUnits() {
        val c = IdrPictureCollector<Int>(maxUnits = 2, maxBytes = 100)
        c.offer(1, first, 40); c.offer(2, first.copy(firstMb = 20), 40)
        c.offer(3, first.copy(firstMb = 30), 10)
        assertEquals(0, c.size); assertNull(c.offer(4, next, 10))
        c.offer(5, first, 101)
        assertEquals(0, c.size); assertNull(c.offer(6, next, 10))
    }
    @Test fun failedRebuildCannotAutomaticallyRetryOrReportColorCorrectness() {
        val repair = VideoRepair<Int>()
        repair.request(100); repair.offer(1, first, 20, 110, true)
        repair.offer(2, next, 20, 120, true); repair.started()
        repair.tick(3120)
        assertEquals(VideoRepairState.FAILED, repair.snapshot().state)
        repair.presented(4000)
        assertEquals(VideoRepairState.FAILED, repair.snapshot().state)
        assertNull(repair.offer(3, first, 20, 5000, true))
        assertEquals(1L, repair.snapshot().attempts)
        repair.stop(5100); assertFalse(repair.request(40_000))
    }
}
