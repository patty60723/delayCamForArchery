package com.archery.delaycam.camera

import org.junit.Assert.*
import org.junit.Test

class RecordingLifecycleTest {
    @Test fun backgroundDuringStartStillStops() {
        val lifecycle = RecordingLifecycle()
        assertTrue(lifecycle.beginStart())
        assertTrue(lifecycle.requestStop(100))
        lifecycle.started()
        assertEquals(RecordingPhase.STOPPING, lifecycle.phase)
        assertFalse(lifecycle.beginStart())
        assertTrue(lifecycle.queueStop())
        assertFalse(lifecycle.queueStop())
        lifecycle.finished()
        assertTrue(lifecycle.beginStart())
    }

    @Test fun stopWaitsForTailAndRejectsRepeatedCommands() {
        val lifecycle = RecordingLifecycle()
        assertTrue(lifecycle.beginStart())
        assertFalse(lifecycle.beginStart())
        lifecycle.started()
        assertTrue(lifecycle.requestStop(5_000))
        assertFalse(lifecycle.requestStop(6_000))
        assertFalse(lifecycle.reachesStopBoundary(4_999))
        assertTrue(lifecycle.acceptsSegments())
        assertTrue(lifecycle.reachesStopBoundary(5_000))
        assertTrue(lifecycle.queueStop())
        assertFalse(lifecycle.acceptsSegments())
        lifecycle.finished()
        assertFalse(lifecycle.queueStop())
    }
}
