package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test

class VideoFrameTrackerTest {
    @Test fun multipleSlicesPerPictureDoNotAccumulateFakePendingFrames() {
        val tracker = VideoFrameTracker()
        repeat(2000) { frame ->
            repeat(4) { slice -> tracker.record(frame * 33333L, frame * 100L + slice) }
            assertEquals(frame * 100L + 3, tracker.take(frame * 33333L))
            assertEquals(0, tracker.size)
        }
    }
    @Test fun codecsThatSkipOutputsCannotGrowTimingMetadataWithoutBound() {
        val tracker = VideoFrameTracker(64)
        repeat(5000) { tracker.record(it.toLong(), it.toLong()) }
        assertEquals(64, tracker.size)
        assertNull(tracker.take(0))
        assertEquals(4999L, tracker.take(4999))
    }
    @Test fun reorderedOutputKeepsMatchingTimingAndNeverGuessesUnknownPts() {
        val tracker = VideoFrameTracker()
        tracker.record(100, 1); tracker.record(300, 2); tracker.record(200, 3)
        assertEquals(3L, tracker.take(200))
        assertNull(tracker.take(999))
        assertEquals(1L, tracker.take(100))
        assertEquals(2L, tracker.take(300))
        tracker.clear(); assertEquals(0, tracker.size)
    }
}
