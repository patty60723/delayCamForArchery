package com.archery.delaycam.gesture

import org.junit.Assert.*
import org.junit.Test

class GestureCommandGateTest {
    private fun release(g: GestureCommandGate, t: Long = 0) {
        assertNull(g.evaluate(null, 0f, t))
        assertNull(g.evaluate(null, 0f, t + 300))
    }
    private fun fire(g: GestureCommandGate, pose: String, t: Long, stable: Long = 600, score: Float = 0.9f): GestureCommand? {
        assertNull(g.evaluate(pose, score, t))
        return g.evaluate(pose, score, t + stable)
    }
    @Test fun sceneMapsPalmToSaveOrReplayAndThumbUpStillRecords() {
        val g = GestureCommandGate()
        g.setScene(GestureScene.LIVE); release(g)
        assertEquals(GestureAction.SAVE_CLIP, fire(g, "Open_Palm", 400, 350, 0.55f)?.action)
        g.setScene(GestureScene.REPLAY); release(g, 1000)
        assertEquals(GestureAction.REPLAY_CLIP, fire(g, "Open_Palm", 1400, score = 0.55f)?.action)
        release(g, 2100)
        assertEquals(GestureAction.TOGGLE_RECORD, fire(g, "Thumb_Up", 2500, 350)?.action)
    }
    @Test fun replayOnlyCommandsAreIgnoredInLiveAndSaving() {
        for (scene in listOf(GestureScene.LIVE, GestureScene.SAVING)) {
            val g = GestureCommandGate(); g.setScene(scene); release(g)
            for (pose in listOf("Thumb_Down", "Victory", "Pointing_Up")) {
                assertNull(fire(g, pose, 1000))
            }
        }
    }
    @Test fun replayCommandMappingAndConfidenceAreIndependentFromRecord() {
        for ((pose, action) in mapOf("Thumb_Down" to GestureAction.RETURN_TO_PRACTICE,
            "Victory" to GestureAction.SLOWER, "Pointing_Up" to GestureAction.FASTER)) {
            val g = GestureCommandGate(); g.setScene(GestureScene.REPLAY); release(g)
            assertNull(fire(g, pose, 400, score = 0.69f))
            assertEquals(action, fire(g, pose, 2000, score = 0.70f)?.action)
        }
    }
    @Test fun heldPalmDoesNotFireTwiceEvenAfterCooldown() {
        val g = GestureCommandGate(); g.setScene(GestureScene.REPLAY); release(g)
        assertNotNull(fire(g, "Open_Palm", 400))
        assertNull(g.evaluate("Open_Palm", 0.9f, 10_000))
        assertNull(g.evaluate("Open_Palm", 0.9f, 11_000))
    }
    @Test fun oneMissingFrameAndLowConfidenceCannotRearm() {
        val g = GestureCommandGate(); g.setScene(GestureScene.REPLAY); release(g)
        assertNotNull(fire(g, "Open_Palm", 400))
        g.evaluate(null, 0f, 1100)
        assertNull(g.evaluate("Open_Palm", 0.9f, 1200))
        assertNull(g.evaluate("Open_Palm", 0.1f, 2000))
        assertNull(g.evaluate("Open_Palm", 0.1f, 3000))
        assertNull(g.evaluate("Open_Palm", 0.9f, 4000))
        assertNull(g.evaluate("Open_Palm", 0.9f, 5000))
        release(g, 6000)
        assertNotNull(fire(g, "Open_Palm", 6400))
    }
    @Test fun palmHeldAcrossNavigationRequiresRelease() {
        val g = GestureCommandGate(); g.setScene(GestureScene.LIVE); release(g)
        assertNotNull(fire(g, "Open_Palm", 400, 350))
        g.setScene(GestureScene.REPLAY)
        assertNull(fire(g, "Open_Palm", 4000))
        release(g, 5000)
        assertEquals(GestureAction.REPLAY_CLIP, fire(g, "Open_Palm", 5400)?.action)
    }
    @Test fun staleInferenceAndQueuedCommandsAreRejected() {
        val g = GestureCommandGate(); g.setScene(GestureScene.REPLAY); release(g)
        val old = g.context()
        val command = fire(g, "Victory", 400)!!
        g.setScene(GestureScene.DISABLED)
        assertFalse(g.isCurrent(command.context))
        g.setScene(GestureScene.REPLAY); release(g, 2000)
        assertNull(g.evaluate("Open_Palm", 0.9f, 4000, old))
    }
    @Test fun recordingStillRequiresThreeSecondCooldown() {
        val g = GestureCommandGate(); g.setScene(GestureScene.REPLAY); release(g)
        assertNotNull(fire(g, "Thumb_Up", 400, 350))
        release(g, 800)
        assertNull(fire(g, "Thumb_Up", 1200, 350))
        assertNotNull(g.evaluate("Thumb_Up", 0.9f, 3800))
    }
    @Test fun returnIsNotBlockedByAnotherActionCooldown() {
        val g = GestureCommandGate(); g.setScene(GestureScene.REPLAY); release(g)
        assertNotNull(fire(g, "Thumb_Up", 400, 350))
        release(g, 800)
        assertEquals(GestureAction.RETURN_TO_PRACTICE, fire(g, "Thumb_Down", 1200)?.action)
    }
    @Test fun savingAllowsOnlyRecordingAndDisabledAcceptsNothing() {
        val g = GestureCommandGate(); g.setScene(GestureScene.SAVING); release(g)
        assertNull(fire(g, "Open_Palm", 400, 350))
        assertNotNull(fire(g, "Thumb_Up", 2000, 350))
        g.setScene(GestureScene.DISABLED); release(g, 5000)
        assertNull(fire(g, "Thumb_Up", 6000, 350))
    }
}
