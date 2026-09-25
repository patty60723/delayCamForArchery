package com.archery.delaycam.gesture

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult

private const val MODEL_ASSET_PATH = "gesture_recognizer.task"
private const val STABLE_MS = 350L
private const val COOLDOWN_MS = 3000L
private const val MIN_SCORE = 0.6f

private const val SAVE_CATEGORY = "Open_Palm"
private const val RECORD_TOGGLE_CATEGORY = "Thumb_Up"
private const val TAG = "GestureEngine"

/**
 * Full-frame gesture triggers (spec 2.2 / 5.3), two independent ones:
 *
 *  - Open_Palm -> per-shot backtrack save (spec 2.2), unchanged from before.
 *  - Thumb_Up  -> start/stop continuous recording (see
 *    camera/ContinuousRecordingSession.kt). Added on user feedback (ported
 *    from the web prototype's gestureEngine.js): doing the save gesture for
 *    every single arrow got tedious, so this gives a "just film the whole
 *    session" option that only needs two gestures total.
 *
 * An earlier design restricted detection to a fixed corner ROI per spec
 * 3.3/5.3, but the web prototype found that too small to reliably land a
 * gesture in - full-frame detection is more forgiving about hand position,
 * so both gestures here scan the whole frame.
 *
 * The two are mutually exclusive per frame (MediaPipe only returns one top
 * category per inference), so evaluating both against the same result is
 * enough - no risk of one frame satisfying both at once. The save gesture is
 * suppressed while `locked` (SAVING/REPLAY, so a stray detection can't
 * re-trigger mid-replay); the record toggle is deliberately NOT gated by
 * `locked` - starting/stopping the continuous recording is independent of
 * the per-shot review flow and should keep working while a replay is on
 * screen, same as the web version.
 *
 * Uses GestureRecognizer's canned categories as the closest built-in match
 * for "五指張開" (open palm) and a natural "start filming" gesture (thumbs
 * up) - MediaPipe ships no bespoke digit-count or camera-control gestures,
 * same reasoning as the web version.
 *
 * Model file is bundled at app/src/main/assets/gesture_recognizer.task (see
 * that folder's README for provenance/refresh instructions). Any init
 * failure - model missing/corrupt, no usable delegate - leaves
 * `available = false`; callers must fall back to the manual buttons,
 * exactly as spec 3.1 describes, and this class never throws out of
 * init()/analyze() to the caller.
 *
 * Takes an already-converted, already-upright Bitmap rather than the raw
 * ImageProxy - MainActivity's analyzer does the YUV_420_888 -> Bitmap
 * conversion once (camera/ImageProxyConversion.kt) and shares the result
 * with ContinuousSegmentEncoder too, rather than each doing its own
 * separate conversion.
 */
class GestureEngine(
    private val context: Context,
    private val onGesture: () -> Unit,
    private val onToggleRecord: () -> Unit,
) {
    var available: Boolean = false
        private set

    /** Set true while SAVING/REPLAY. Gates the SAVE gesture only - see class doc. */
    @Volatile
    var locked: Boolean = false
        set(value) {
            field = value
            if (value) saveState.streakStart = 0L
        }

    private var recognizer: GestureRecognizer? = null
    private val saveState = StreakState()
    private val recordToggleState = StreakState()
    private val mainHandler = Handler(Looper.getMainLooper())

    private class StreakState {
        var streakStart: Long = 0L
        var lastFireAt: Long = 0L
    }

    fun init() {
        recognizer = tryCreate(Delegate.GPU) ?: tryCreate(Delegate.CPU)
        available = recognizer != null
    }

    private fun tryCreate(delegate: Delegate): GestureRecognizer? = try {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET_PATH)
            .setDelegate(delegate)
            .build()
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setResultListener(::onResult)
            .setErrorListener { /* per-frame inference errors are non-fatal, just skip that frame */ }
            .build()
        GestureRecognizer.createFromOptions(context, options)
    } catch (e: Exception) {
        Log.w(TAG, "createFromOptions failed for delegate=$delegate", e)
        null
    }

    /**
     * Feed one live-camera frame in, already converted to an upright Bitmap
     * by the caller (see class doc). Does not own/close anything - the
     * caller's ImageProxy is already closed by the time this runs. Runs
     * regardless of `locked` - see class doc for why.
     */
    fun analyzeBitmap(bitmap: Bitmap, timestampMs: Long) {
        val r = recognizer ?: return
        try {
            val mpImage = BitmapImageBuilder(bitmap).build()
            r.recognizeAsync(mpImage, timestampMs)
        } catch (e: Exception) {
            // decode/inference hiccup on this one frame - skip it, try the next.
            Log.w(TAG, "frame analyze() failed, skipping this frame", e)
        }
    }

    // MediaPipe's LIVE_STREAM result listener fires on its own internal
    // TaskRunner thread, not the main thread (confirmed on-device: a save
    // gesture crashed the app with "Player is accessed on the wrong thread"
    // - ExoPlayer.stop() in triggerSave() was being called straight from
    // that thread). onResult/evaluate below run there too, so everything up
    // through the streak bookkeeping is fine, but the actual onGesture()/
    // onToggleRecord() callback must be posted to the main thread before it
    // touches anything UI-thread-confined like the ViewModel's ExoPlayers.
    private fun onResult(result: GestureRecognizerResult, input: MPImage) {
        val top = result.gestures().firstOrNull()?.firstOrNull()
        val now = System.currentTimeMillis()

        // Diagnostic for "Thumb_Up works, Open_Palm doesn't": Thumb_Up isn't
        // gated by `locked` but Open_Palm is, so if `locked` were ever stuck
        // true this alone would explain the asymmetry. Logging whenever a
        // category is seen close to the save threshold either way settles
        // it: never seeing "Open_Palm" near/above MIN_SCORE means it's a
        // detection/threshold problem, seeing it there but still not firing
        // means locked (or triggerSave()'s own guards) is the culprit.
        if (top != null && (top.categoryName() == SAVE_CATEGORY || top.score() >= 0.4f)) {
            Log.d(TAG, "top gesture=${top.categoryName()} score=${top.score()} locked=$locked")
        }

        if (!locked) {
            val isSaveGesture = top != null && top.categoryName() == SAVE_CATEGORY && top.score() >= MIN_SCORE
            evaluate(saveState, isSaveGesture, now, onGesture)
        }

        val isRecordToggleGesture = top != null && top.categoryName() == RECORD_TOGGLE_CATEGORY && top.score() >= MIN_SCORE
        evaluate(recordToggleState, isRecordToggleGesture, now, onToggleRecord)
    }

    private fun evaluate(state: StreakState, matched: Boolean, now: Long, onFire: () -> Unit) {
        if (!matched) {
            state.streakStart = 0L
            return
        }
        if (state.streakStart == 0L) state.streakStart = now
        val heldFor = now - state.streakStart
        if (heldFor >= STABLE_MS && now - state.lastFireAt >= COOLDOWN_MS) {
            state.lastFireAt = now
            state.streakStart = 0L
            mainHandler.post(onFire)
        }
    }

    fun close() {
        recognizer?.close()
        recognizer = null
        available = false
    }
}
