package com.archery.delaycam.camera

import android.content.Context
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Rolling buffer (spec 2.1 / 5.2): records to short, ~2s segment files that
 * cycle through a fixed-size ring in the app's private cache dir (not shown
 * to the user - spec 6 keeps these separate from permanently saved clips).
 *
 * CONFIRMED ON DEVICE (logcat), root cause and all: reusing one Recorder
 * for rapid stop()/start() cycling produces a sustained native
 * "MPEG4Writer: Stop() called but track is not started or stopped"
 * warning at roughly the camera's frame rate, for the whole session, not
 * just a blip at each boundary - and, decisively, "[Utils] Inconsisted
 * profile/level found in SPS: 64,0,1f vs ea,8f,2c": consecutive segment
 * Recorder sessions on this device really were landing on different
 * H.264 SPS profile/level, not just racing at the muxer/log level. That's
 * what was breaking playback/export at segment boundaries (the delayed
 * view going black partway through a segment transition).
 * Two mitigations layered on top of that finding so far:
 *  - segmentDurationMs widened from 1000ms to 2000ms, to roughly halve
 *    how often the stop/start boundary (and this race) happens per
 *    second of footage - confirmed on a later logcat to actually halve
 *    the warning rate, but does not eliminate the underlying SPS drift.
 *  - MainActivity's Recorder.Builder now pins setTargetVideoEncodingBitRate
 *    explicitly, removing one axis the encoder was free to renegotiate
 *    per session, in case the drift was bitrate-adaptation-driven.
 * If segments are still visibly corrupting after both, the SPS drift
 * isn't bitrate-driven and the only real fix is to stop tearing down/
 * rebuilding the encoder every segment at all - keep one MediaCodec
 * encoder alive continuously and only rotate the MediaMuxer/output file
 * at segment boundaries (requesting a sync frame right at the cut), which
 * means bypassing CameraX's Recorder for this rather than configuring it
 * differently.
 *
 * NOT YET VERIFIED ON DEVICE. Hitting the spec's 60fps target (5.1) needs
 * the camera session's frame rate range pinned via Camera2Interop
 * (CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE) on the Recorder's
 * SessionConfig - not wired up here yet. Without it, CameraX will pick a
 * device-default rate.
 */
class RollingSegmentRecorder(
    private val context: Context,
    private val cacheDir: File,
    private val onSegmentFinalized: (SegmentInfo) -> Unit,
    private val segmentDurationMs: Long = 2000L,
    private var ringSize: Int = 10,
) {
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var loopJob: Job? = null
    private var segmentIndex = 0

    fun attach(videoCapture: VideoCapture<Recorder>) {
        this.videoCapture = videoCapture
    }

    fun setRingSize(size: Int) {
        ringSize = size.coerceAtLeast(2)
    }

    fun start(scope: CoroutineScope) {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                recordOneSegment()
            }
        }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        activeRecording?.stop()
        activeRecording = null
    }

    private suspend fun recordOneSegment() {
        val capture = videoCapture ?: run {
            delay(segmentDurationMs)
            return
        }
        val file = segmentFile(segmentIndex)
        segmentIndex = (segmentIndex + 1) % ringSize
        val startedAtMs = System.currentTimeMillis()
        val finalized = CompletableDeferred<Unit>()

        val output = FileOutputOptions.Builder(file).build()
        val recording = capture.output
            .prepareRecording(context, output)
            .start(ContextCompat.getMainExecutor(context)) { event ->
                if (event is VideoRecordEvent.Finalize) {
                    if (!event.hasError()) {
                        onSegmentFinalized(SegmentInfo(file, startedAtMs, System.currentTimeMillis()))
                    }
                    if (!finalized.isCompleted) finalized.complete(Unit)
                }
            }
        activeRecording = recording

        delay(segmentDurationMs)
        recording.stop()
        finalized.await()
    }

    /** rollbuf_<index>.mp4 - overwritten in place each time its slot comes back around. */
    private fun segmentFile(index: Int): File = File(cacheDir, "rollbuf_segment_$index.mp4")
}
