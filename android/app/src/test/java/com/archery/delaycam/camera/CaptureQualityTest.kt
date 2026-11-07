package com.archery.delaycam.camera

import org.junit.Assert.*
import org.junit.Test

class CaptureQualityTest {
    @Test fun rotationUsesIdenticalBoundedCanvasAndFallbacksOnlyReduceSize() {
        for ((w, h) in listOf(1280 to 960, 640 to 480, 1920 to 1080, 400 to 300)) {
            val sizes = encoderCanvasCandidates(w, h)
            assertEquals(sizes, encoderCanvasCandidates(h, w))
            assertTrue(sizes.first() <= 1280)
            assertTrue(sizes.zipWithNext().all { (a, b) -> a > b })
            assertTrue(sizes.all { it % 16 == 0 })
        }
        assertEquals(1280, encoderCanvasCandidates(960, 1280).first())
        assertEquals(1280 to 720, captureSize(1280, 1280, 16f / 9f))
        assertEquals(720 to 1280, captureSize(1280, 1280, 9f / 16f))
    }

    @Test fun captureBudgetAllows720pCropButRejectsUnboundedCameraFallbacks() {
        assertTrue(withinCaptureBudget(1280, 960))
        assertTrue(withinCaptureBudget(960, 1280))
        assertTrue(withinCaptureBudget(1280, 720))
        assertTrue(withinCaptureBudget(640, 480))
        assertFalse(withinCaptureBudget(1920, 1080))
        assertFalse(withinCaptureBudget(1280, 1024))
    }
}
