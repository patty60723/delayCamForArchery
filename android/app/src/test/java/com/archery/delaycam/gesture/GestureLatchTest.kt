package com.archery.delaycam.gesture

import org.junit.Assert.*
import org.junit.Test

class GestureLatchTest {
    @Test fun tenSecondHoldFiresOnce() {
        val latch = GestureLatch(350, 3_000)
        val fires = (0L..10_000L step 50).count { latch.evaluate(true, it) }
        assertEquals(1, fires)
        latch.evaluate(false, 10_100)
        assertFalse(latch.evaluate(true, 10_200))
        assertTrue(latch.evaluate(true, 10_550))
    }

    @Test fun reentryDoesNotFireHeldGestureUntilRelease() {
        val latch = GestureLatch(350, 3_000)
        latch.suspendUntilRelease()
        assertFalse(latch.evaluate(true, 10_000))
        latch.evaluate(false, 10_001)
        latch.evaluate(true, 10_100)
        assertTrue(latch.evaluate(true, 10_450))
    }
}
