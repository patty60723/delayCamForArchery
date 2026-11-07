package com.archery.delaycam.camera

import com.archery.delaycam.settings.AppSettings
import com.archery.delaycam.settings.CaptureAspect
import org.junit.Assert.*
import org.junit.Test

class CaptureSizeTest {
    @Test fun commonFormatsHaveExactRatioEvenDimensionsAndStayInsideCameraSource() {
        for ((width, height) in listOf(640 to 480, 480 to 640, 1920 to 1080, 1080 to 1920)) {
            for (mode in CaptureAspect.entries) {
                val aspect = mode.ratioFor(width, height)
                val size = captureSize(width, height, aspect)
                assertEquals(aspect, size.first.toFloat() / size.second, .0001f)
                assertEquals(0, size.first % 2); assertEquals(0, size.second % 2)
                assertTrue(size.first <= width && size.second <= height)
            }
        }
    }
    @Test fun newInstallKeepsFourThreeAndPortraitReversesTheFraction() {
        assertEquals(CaptureAspect.FOUR_THREE, AppSettings().captureAspect)
        assertEquals(9f / 16f, CaptureAspect.SIXTEEN_NINE.ratioFor(480, 640), .0001f)
        assertEquals(16f / 9f, CaptureAspect.SIXTEEN_NINE.ratioFor(640, 480), .0001f)
    }
}
