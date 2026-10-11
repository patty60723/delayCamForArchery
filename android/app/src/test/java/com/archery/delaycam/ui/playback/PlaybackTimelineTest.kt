package com.archery.delaycam.ui.playback

import org.junit.Assert.*
import org.junit.Test

class PlaybackTimelineTest {
    @Test fun seeksAcrossSegmentBoundaries() {
        val durations = listOf(2_000L, 3_000L, 1_000L)
        assertEquals(6_000L, PlaybackTimeline.duration(durations))
        assertEquals(2_500L, PlaybackTimeline.position(durations, 1, 500))
        assertEquals(1 to 0L, PlaybackTimeline.target(durations, 2_000))
        assertEquals(1 to 500L, PlaybackTimeline.target(durations, 2_500))
        assertEquals(2 to 1_000L, PlaybackTimeline.target(durations, 9_000))
        assertEquals(0 to 0L, PlaybackTimeline.target(durations, -100))
    }

    @Test fun handlesEmptyAndUnknownDurations() {
        assertEquals(0 to 0L, PlaybackTimeline.target(emptyList(), 10))
        assertEquals(1 to 500L, PlaybackTimeline.target(listOf(-1, 2_000), 500))
        assertEquals(0L, PlaybackTimeline.position(emptyList(), 0, 500))
    }
}
