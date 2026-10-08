package com.kanayama.wifiscreen

import org.junit.Assert.*
import org.junit.Test

class VideoViewportTest {
    @Test fun portraitFitPreservesFullImage() {
        assertEquals(VideoBounds(608, 1080, 656, 0),
            VideoViewport.bounds(1920, 1080, 1080, 1920, PictureSettings()))
    }
    @Test fun portraitFillCropsEquallyAndCoversScreen() {
        assertEquals(VideoBounds(1920, 3413, 0, -1166),
            VideoViewport.bounds(1920, 1080, 1080, 1920, PictureSettings(PictureMode.FILL)))
    }
    @Test fun manualPanStopsAtImageEdges() {
        assertEquals(VideoBounds(3840, 2160, 0, -1080), VideoViewport.bounds(1920, 1080,
            1920, 1080, PictureSettings(PictureMode.MANUAL, 2f, 1f, -1f)))
    }
    @Test fun invalidPreferencesCannotProduceInvalidSurface() {
        assertNull(VideoViewport.bounds(0, 1080, 1920, 1080, PictureSettings()))
        assertEquals(VideoBounds(1920, 1080, 0, 0), VideoViewport.bounds(1920, 1080,
            1920, 1080, PictureSettings(PictureMode.MANUAL, Float.NaN, Float.POSITIVE_INFINITY)))
    }
}
