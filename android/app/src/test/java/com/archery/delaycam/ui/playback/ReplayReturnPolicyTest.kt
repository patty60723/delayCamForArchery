package com.archery.delaycam.ui.playback

import org.junit.Assert.*
import org.junit.Test

class ReplayReturnPolicyTest {
    @Test fun firstViewingHasTwoSecondsGraceAndRepeatedEndedDoesNotExtendIt() {
        val p = ReplayReturnPolicy(); p.begin(); p.ended(100)
        assertEquals(2, p.remainingSeconds(100))
        p.ended(1000)
        assertEquals(1, p.remainingSeconds(1100))
        assertFalse(p.shouldReturn(2099)); assertTrue(p.shouldReturn(2100))
    }
    @Test fun speedOrReplayInteractionCancelsReturnIncludingOldEndedCallbacks() {
        val p = ReplayReturnPolicy(); p.begin(); p.ended(0); p.interact()
        p.ended(5000)
        assertNull(p.remainingSeconds(5000)); assertFalse(p.shouldReturn(10000))
        p.begin(); p.ended(10000); assertTrue(p.shouldReturn(12000))
    }
    @Test fun backgroundCancelsDeadlineAndForegroundGetsANewGracePeriod() {
        val p = ReplayReturnPolicy(); p.begin(); p.ended(0); p.suspend()
        assertFalse(p.shouldReturn(10000))
        p.ended(10000); assertEquals(2, p.remainingSeconds(10000))
    }
}
