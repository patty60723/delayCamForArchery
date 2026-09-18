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

/**
 * Full-frame gesture trigger (spec 2.2 / 5.3). An earlier design restricted
 * detection to a fixed corner ROI per spec 3.3/5.3, but the web prototype
 * (gestureEngine.js) switched to scanning the whole frame after finding the
 * corner box too small to reliably land a gesture in - this mirrors that
 * decision natively.
 *
 * Uses GestureRecognizer's Open_Palm category as the closest built-in match
 * for "五指張開" (open/spread hand) - MediaPipe ships no bespoke "five"
 * digit-count gesture, same reasoning as the web version.
 *
 * REQUIRES `gesture_recognizer.task` bundled at app/src/main/assets/ (see
 * android/README.md for the download URL). Any init failure - missing
 * model, no usable delegate - leaves `available = false`; callers must fall
 * back to the manual save button, exactly as spec 3.1 describes, and this
 * class never throws out of init()/analyze() to the caller.
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
) {
    var available: Boolean = false
        private set

    /** Set true while SAVING/REPLAY so a straggling detection can't re-fire mid-replay. */
    @Volatile var locked: Boolean = false

    private var recognizer: GestureRecognizer? = null
    private var streakStartMs: Long = 0L
    private var lastFireAtMs: Long = 0L

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

    /** Feed one live-camera frame in from an ImageAnalysis.Analyzer. Always closes imageProxy. */
    fun analyze(imageProxy: ImageProxy) {
        val r = recognizer
        if (r == null || locked) {
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
        if (locked) return
        val top = result.gestures().firstOrNull()?.firstOrNull()
        val isOpenPalm = top != null && top.categoryName() == "Open_Palm" && top.score() >= MIN_SCORE
        val now = System.currentTimeMillis()

        if (!isOpenPalm) {
            streakStartMs = 0L
            return
        }
        if (streakStartMs == 0L) streakStartMs = now
        val heldFor = now - streakStartMs
        if (heldFor >= STABLE_MS && now - lastFireAtMs >= COOLDOWN_MS) {
            lastFireAtMs = now
            streakStartMs = 0L
            onGesture()
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
