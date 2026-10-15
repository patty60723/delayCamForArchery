package com.archery.delaycam.gesture

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult

private const val MODEL_ASSET_PATH = "gesture_recognizer.task"
private const val TAG = "GestureEngine"

/** One model reads live camera poses; commands are routed and revalidated by scene. */
class GestureEngine(
    private val context: Context,
    private val onAction: (GestureAction, GestureScene) -> Unit,
) {
    var available: Boolean = false
        private set

    private val gate = GestureCommandGate()
    private val frameContexts = LinkedHashMap<Long, GestureContext>()

    fun setScene(scene: GestureScene) { gate.setScene(scene) }

    private var recognizer: GestureRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())


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
    } catch (e: LinkageError) {
        // Some ABIs/delegate installations have no compatible native library.
        // This includes the subsequent NoClassDefFoundError after a failed JNI
        // static initializer. Keep camera/playback and manual controls usable.
        Log.w(TAG, "native gesture library unavailable for delegate=$delegate", e)
        null
    }

    fun analyzeBitmap(bitmap: Bitmap, timestampMs: Long) {
        val r = recognizer ?: return
        val context = gate.context()
        if (context.scene == GestureScene.DISABLED) return
        synchronized(frameContexts) {
            frameContexts[timestampMs] = context
            // LIVE_STREAM may drop frames: bounded bookkeeping cannot leak dropped requests.
            while (frameContexts.size > 64) frameContexts.remove(frameContexts.keys.first())
        }
        try {
            // Inference owns a small independent image. Never retain the high-resolution
            // capture bitmap asynchronously; the caller may recycle it after encoding.
            r.recognizeAsync(BitmapImageBuilder(gestureFrameCopy(bitmap)).build(), timestampMs)
        } catch (e: Exception) {
            synchronized(frameContexts) { frameContexts.remove(timestampMs) }
            Log.w(TAG, "frame analyze() failed, skipping this frame", e)
        }
    }

    private fun onResult(result: GestureRecognizerResult, input: MPImage) {
        val context = synchronized(frameContexts) { frameContexts.remove(result.timestampMs()) } ?: return
        if (!gate.isCurrent(context)) return
        val top = result.gestures().firstOrNull()?.firstOrNull()
        if (top != null && top.score() >= 0.4f) {
            Log.d(TAG, "top gesture=${top.categoryName()} score=${top.score()} scene=${context.scene}")
        }
        val command = gate.evaluate(top?.categoryName(), top?.score() ?: 0f,
            SystemClock.elapsedRealtime(), context) ?: return
        mainHandler.post {
            // A queued inference or command must not acquire a new meaning after navigation.
            if (gate.isCurrent(command.context)) onAction(command.action, command.context.scene)
        }
    }

    fun close() {
        recognizer?.close()
        recognizer = null
        gate.setScene(GestureScene.DISABLED)
        synchronized(frameContexts) { frameContexts.clear() }
        available = false
    }
}

internal fun gestureFrameCopy(bitmap: Bitmap): Bitmap {
    val scale = minOf(1f, 640f / maxOf(bitmap.width, bitmap.height))
    val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
    val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
    return if (resized === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else resized
}

