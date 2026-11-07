package com.archery.delaycam.camera

import com.archery.delaycam.settings.CaptureAspect.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class CameraSourcePolicyTest {
    @Test fun preferredSourceSucceedsWithoutTryingFallback() {
        val attempts = mutableListOf<Any>()
        val result = bindPreferredSource(SIXTEEN_NINE) { attempts.add(it); "bound" }
        assertEquals(listOf(SIXTEEN_NINE), attempts)
        assertEquals(SIXTEEN_NINE to "bound", result)
    }
    @Test fun unsupportedWideSourceFallsBackOnceAndReturnsActualSource() {
        val attempts = mutableListOf<Any>()
        val result = bindPreferredSource(SIXTEEN_NINE) {
            attempts.add(it)
            if (it == SIXTEEN_NINE) throw IllegalArgumentException("unsupported stream combination")
            "four-three"
        }
        assertEquals(listOf(SIXTEEN_NINE, FOUR_THREE), attempts)
        assertEquals(FOUR_THREE to "four-three", result)
    }
    @Test fun exhaustedAttemptsThrowLastFailureWithoutRetryLoop() {
        val attempts = mutableListOf<Any>()
        val last = IllegalArgumentException("4:3 unavailable")
        try {
            bindPreferredSource(SIXTEEN_NINE) {
                attempts.add(it)
                if (it == FOUR_THREE) throw last else throw IllegalStateException("16:9 unavailable")
            }
            fail("Both attempts fail")
        } catch (e: IllegalArgumentException) { assertSame(last, e) }
        assertEquals(listOf(SIXTEEN_NINE, FOUR_THREE), attempts)
        var count = 0
        try { bindPreferredSource(FOUR_THREE) { count++; throw last } }
        catch (e: IllegalArgumentException) { assertSame(last, e) }
        assertEquals(1, count)
    }
    @Test fun permissionFailureAndCancellationMustNotTriggerAnotherCameraAttempt() {
        for (failure in listOf(SecurityException("permission"), CancellationException("stopped"))) {
            var count = 0
            try { bindPreferredSource(SIXTEEN_NINE) { count++; throw failure } }
            catch (e: Exception) { assertSame(failure, e) }
            assertEquals(1, count)
        }
    }
    @Test fun sensorSizesMatchTheRequestedRatioInEitherOrientation() {
        assertTrue(matchesSourceAspect(1280, 720, SIXTEEN_NINE))
        assertTrue(matchesSourceAspect(720, 1280, SIXTEEN_NINE))
        assertFalse(matchesSourceAspect(1280, 960, SIXTEEN_NINE))
        assertTrue(matchesSourceAspect(640, 480, FOUR_THREE))
        assertFalse(matchesSourceAspect(1920, 1088, SIXTEEN_NINE))
    }
}
