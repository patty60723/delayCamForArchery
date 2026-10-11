package com.archery.delaycam.camera

import org.junit.Assert.*
import org.junit.Test

class SegmentTimelineTest {
    @Test fun joinsAbsoluteAndZeroBasedTimestampsWithoutGaps() {
        val timeline = SegmentTimeline()
        timeline.beginSegment()
        assertEquals(0L, timeline.map(9_000_000))
        assertEquals(33_333L, timeline.map(9_033_333))
        timeline.endSegment()
        timeline.beginSegment()
        assertEquals(66_666L, timeline.map(90_000_000))
        assertEquals(99_999L, timeline.map(90_033_333))
        timeline.endSegment()
        timeline.beginSegment()
        assertEquals(133_332L, timeline.map(0))
    }

    @Test fun emptySegmentDoesNotResetTimeline() {
        val timeline = SegmentTimeline()
        timeline.beginSegment()
        timeline.map(1_000)
        timeline.endSegment()
        timeline.beginSegment()
        timeline.endSegment()
        timeline.beginSegment()
        assertEquals(33_333L, timeline.map(100))
        assertTrue(timeline.map(99) > 33_333L)
    }
}
