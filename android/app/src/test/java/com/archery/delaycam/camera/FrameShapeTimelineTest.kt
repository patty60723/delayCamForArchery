package com.archery.delaycam.camera

import org.junit.Assert.*
import org.junit.Test

class FrameShapeTimelineTest {
    @Test fun delayedEncoderOutputUsesSubmittedFrameShapeNotCurrentPhoneOrientation() {
        val shapes = FrameShapeTimeline()
        shapes.submitted(100, .75f)
        shapes.submitted(300, 4f / 3f)
        assertNull(shapes.at(99))
        assertEquals(.75f, shapes.at(120))
        assertEquals(.75f, shapes.at(299))
        assertEquals(4f / 3f, shapes.at(301))
        shapes.submitted(500, .75f)
        assertEquals(4f / 3f, shapes.at(450))
        assertEquals(.75f, shapes.at(501))
    }
}
