package com.archery.delaycam.gesture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.io.ByteArrayOutputStream

private const val MODEL_ASSET_PATH = "gesture_recognizer.task"
private const val STABLE_MS = 350L
private const val COOLDOWN_MS = 3000L
private const val MIN_SCORE = 0.6f

private const val SAVE_CATEGORY = "Open_Palm"
private const val RECORD_TOGGLE_CATEGORY = "Thumb_Up"

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
 * NOT YET VERIFIED ON DEVICE. The YUV_420_888 -> NV21 conversion below
 * assumes pixelStride == 1 on the U/V planes, true for most devices' CameraX
 * ImageAnalysis output but not guaranteed on every device - if colors look
 * wrong (not detection failing outright, actual visible color corruption if
 * you render the converted bitmap), switch to a stride-aware conversion.
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
        null
    }

    /**
     * Feed one live-camera frame in from an ImageAnalysis.Analyzer. Always
     * closes imageProxy. Runs regardless of `locked` - see class doc for why.
     */
    fun analyze(imageProxy: ImageProxy) {
        val r = recognizer
        if (r == null) {
            imageProxy.close()
            return
        }
        try {
            val bitmap = imageProxy.toBitmapViaYuv()
            val rotated = rotateIfNeeded(bitmap, imageProxy.imageInfo.rotationDegrees)
            val mpImage = BitmapImageBuilder(rotated).build()
            r.recognizeAsync(mpImage, imageProxy.imageInfo.timestamp / 1_000_000L)
        } catch (e: Exception) {
            // decode/inference hiccup on this one frame - skip it, try the next
        } finally {
            imageProxy.close()
        }
    }

    private fun rotateIfNeeded(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun onResult(result: GestureRecognizerResult, input: MPImage) {
        val top = result.gestures().firstOrNull()?.firstOrNull()
        val now = System.currentTimeMillis()

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
            onFire()
        }
    }

    fun close() {
        recognizer?.close()
        recognizer = null
        available = false
    }
}

private fun ImageProxy.toBitmapViaYuv(): Bitmap {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
    val jpegBytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
}
