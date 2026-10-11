package com.archery.delaycam.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundCaptureSessionTest {
    @Test
    fun framesWaitForCurrentEncoderStartup() {
        val capture = ForegroundCaptureSession()
        assertNull(capture.sessionForFrame())
        val session = capture.beginForeground()!!
        assertTrue(capture.isCurrent(session))
        assertNull(capture.sessionForFrame())
        assertTrue(capture.markReady(session))
        assertTrue(capture.sessionForFrame() == session)
    }

    @Test
    fun goingHomeImmediatelyInvalidatesFramesAndCallbacks() {
        val capture = ForegroundCaptureSession()
        val session = capture.beginForeground()!!
        capture.markReady(session)
        assertTrue(capture.endForeground())
        assertFalse(capture.isCurrent(session))
        assertFalse(capture.markReady(session))
        assertNull(capture.sessionForFrame())
    }

    @Test
    fun returningToAppRejectsStartupAndCallbacksFromOldEncoder() {
        val capture = ForegroundCaptureSession()
        val oldSession = capture.beginForeground()!!
        capture.markReady(oldSession)
        capture.endForeground()
        val newSession = capture.beginForeground()!!
        assertNotEquals(oldSession, newSession)
        assertFalse(capture.markReady(oldSession))
        assertFalse(capture.isCurrent(oldSession))
        assertNull(capture.sessionForFrame())
        assertTrue(capture.markReady(newSession))
        assertTrue(capture.sessionForFrame() == newSession)
    }

    @Test
    fun repeatedForegroundNotificationDoesNotResetRunningCapture() {
        val capture = ForegroundCaptureSession()
        val session = capture.beginForeground()!!
        capture.markReady(session)
        assertNull(capture.beginForeground())
        assertTrue(capture.sessionForFrame() == session)
    }

    @Test
    fun rapidHomeReturnHomeReturnAcceptsOnlyLastSession() {
        val capture = ForegroundCaptureSession()
        val first = capture.beginForeground()!!
        capture.endForeground()
        val second = capture.beginForeground()!!
        capture.endForeground()
        val third = capture.beginForeground()!!
        assertFalse(capture.markReady(first))
        assertFalse(capture.markReady(second))
        assertFalse(capture.isCurrent(first))
        assertFalse(capture.isCurrent(second))
        assertTrue(capture.markReady(third))
        assertTrue(capture.sessionForFrame() == third)
    }

    @Test
    fun duplicateBackgroundNotificationIsHarmless() {
        val capture = ForegroundCaptureSession()
        capture.beginForeground()
        assertTrue(capture.endForeground())
        assertFalse(capture.endForeground())
        assertFalse(capture.isForeground())
        assertNull(capture.sessionForFrame())
    }

    @Test
    fun closingViewModelRejectsLateCallbacksAndAnyNewStartup() {
        val capture = ForegroundCaptureSession()
        val session = capture.beginForeground()!!
        capture.markReady(session)
        capture.close()
        assertNull(capture.beginForeground())
        assertFalse(capture.isCurrent(session))
        assertFalse(capture.markReady(session))
        assertNull(capture.sessionForFrame())
    }
}
