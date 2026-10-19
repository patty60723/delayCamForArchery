package com.archery.delaycam.camera

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FastResumePlanTest {
    private fun segment(start: Long, end: Long) = SegmentInfo(File("$start.mp4"), start, end)
    private val source = (0L..18L step 2).map { segment(it * 1000, (it + 2) * 1000) }

    @Test fun returnsToNowMinusDelayInsteadOfPausedPosition() {
        val plan = FastResumePlan.create(source, 21_500, 5_000)!!
        assertEquals(16_000L, plan.segments.first().startedAtMs)
        assertEquals(500L, plan.positionMs)
        assertEquals(2, plan.segments.size)
    }

    @Test fun boundarySelectsNextSegmentAndIgnoresGapsOutsideSelectedWindow() {
        val plan = FastResumePlan.create(listOf(segment(0, 1_000)) + source.drop(5), 21_000, 5_000)!!
        assertEquals(16_000L, plan.segments.first().startedAtMs)
        assertEquals(0L, plan.positionMs)
    }

    @Test fun gapOverlapAndReusedFilenameCannotResume() {
        assertNull(FastResumePlan.create(listOf(segment(14_000, 16_000), segment(17_000, 19_000)), 20_000, 5_000))
        assertNull(FastResumePlan.create(listOf(segment(14_000, 16_000), segment(15_900, 18_000)), 20_000, 5_000))
        val a = segment(14_000, 16_000)
        assertNull(FastResumePlan.create(listOf(a, a.copy(startedAtMs = 16_000, endedAtMs = 18_000)), 20_000, 5_000))
    }

    @Test fun missingHistoryStaleClockAndUnfinishedTargetFallBack() {
        assertNull(FastResumePlan.create(source, 21_000, 30_000))
        assertNull(FastResumePlan.create(source, 30_000, 15_000))
        assertNull(FastResumePlan.create(source, 18_000, 5_000))
        assertNull(FastResumePlan.create(source, 21_000, 1_000))
        assertNull(FastResumePlan.create(source, 20_100, 300))
    }

    @Test fun suspectMediaDurationFallsBackInsteadOfSeekingWrongFrame() {
        val bad = source.map { it.copy(mediaDurationMs = 500) }
        assertNull(FastResumePlan.create(bad, 21_000, 5_000))
    }

    @Test fun positionUsesRecordedDurationWithinAcceptedClockTolerance() {
        val plan = FastResumePlan.create(listOf(segment(14_000, 16_000).copy(mediaDurationMs = 1_800),
            segment(16_000, 18_000)), 20_000, 5_000)!!
        assertEquals(900L, plan.positionMs)
    }

    @Test fun backgroundReturnAcceptsCompletedLongerGopWithoutChangingNormalReplayPolicy() {
        val long = listOf(segment(10_000,17_000),segment(17_000,19_000))
        assertNull(FastResumePlan.create(long,20_000,6_000))
        val plan = FastResumePlan.create(long,20_000,6_000,FastResumePlan.BACKGROUND_MAX_SEGMENT_MS)!!
        assertEquals(4_000L,plan.positionMs)
        assertEquals(15_000L,plan.maxSegmentMs)
        assertEquals(long,plan.segments)
    }

    @Test fun longerBackgroundGopStillRejectsInvalidTimingAndUnboundedDuration() {
        val badClock = listOf(segment(10_000,17_000).copy(mediaDurationMs=1_000),segment(17_000,19_000))
        assertNull(FastResumePlan.create(badClock,20_000,6_000,15_000))
        assertNull(FastResumePlan.create(listOf(segment(0,20_000)),21_000,6_000,15_000))
        assertNull(FastResumePlan.create(listOf(segment(10_000,17_000),segment(17_500,19_000)),20_000,6_000,15_000))
    }

    @Test fun acceptingLongerGopDoesNotAllowStaleOrUnfinishedBackgroundTargets() {
        val long = listOf(segment(10_000,17_000),segment(17_000,19_000))
        assertNull(FastResumePlan.create(long,26_000,12_000,15_000))
        assertNull(FastResumePlan.create(long,21_000,1_000,15_000))
    }

}
