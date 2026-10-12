package com.archery.delaycam.gesture

enum class GestureScene { DISABLED, LIVE, SAVING, REPLAY }
enum class GestureAction { SAVE_CLIP, TOGGLE_RECORD, RETURN_TO_PRACTICE, REPLAY_CLIP, FASTER, SLOWER }
data class GestureContext(val scene: GestureScene, val revision: Long)
data class GestureCommand(val action: GestureAction, val context: GestureContext)

/** A scene change or fired command requires a stable neutral pose, never a single missed frame. */
class GestureCommandGate {
    private var current = GestureContext(GestureScene.DISABLED, 0)
    private var awaitingRelease = true
    private var releaseStart: Long? = null
    private var candidate: GestureAction? = null
    private var candidateStart = 0L
    private val lastFire = mutableMapOf<GestureAction, Long>()
    private val controlCategories = setOf("Open_Palm", "Thumb_Up", "Thumb_Down", "Pointing_Up", "Victory")

    @Synchronized fun setScene(scene: GestureScene) {
        if (scene == current.scene) return
        current = GestureContext(scene, current.revision + 1)
        awaitingRelease = true
        releaseStart = null
        candidate = null
    }

    @Synchronized fun context(): GestureContext = current
    @Synchronized fun isCurrent(context: GestureContext): Boolean =
        current == context && current.scene != GestureScene.DISABLED

    @Synchronized fun evaluate(
        category: String?, score: Float, nowMs: Long, context: GestureContext = current,
    ): GestureCommand? {
        if (!isCurrent(context)) return null
        val action = when {
            category == "Thumb_Up" && score >= 0.70f -> GestureAction.TOGGLE_RECORD
            current.scene == GestureScene.LIVE && category == "Open_Palm" && score >= 0.55f -> GestureAction.SAVE_CLIP
            current.scene == GestureScene.REPLAY && category == "Open_Palm" && score >= 0.55f -> GestureAction.REPLAY_CLIP
            current.scene == GestureScene.REPLAY && score >= 0.70f -> when (category) {
                "Thumb_Down" -> GestureAction.RETURN_TO_PRACTICE
                "Pointing_Up" -> GestureAction.FASTER
                "Victory" -> GestureAction.SLOWER
                else -> null
            }
            else -> null
        }
        if (action == null) {
            candidate = null
            // Confidence jitter on the same control pose is not evidence that the hand was lowered.
            if (category != null && category in controlCategories) {
                releaseStart = null
                return null
            }
            val start = releaseStart ?: nowMs.also { releaseStart = it }
            if (nowMs - start >= 300L) awaitingRelease = false
            return null
        }
        releaseStart = null
        if (awaitingRelease) return null
        if (candidate != action) {
            candidate = action
            candidateStart = nowMs
        }
        val original = action == GestureAction.SAVE_CLIP || action == GestureAction.TOGGLE_RECORD
        val stableMs = if (original) 350L else 600L
        val cooldownMs = if (original) 3000L else 800L
        if (nowMs - candidateStart < stableMs || lastFire[action]?.let { nowMs - it < cooldownMs } == true) return null
        lastFire[action] = nowMs
        awaitingRelease = true
        candidate = null
        return GestureCommand(action, current)
    }
}
