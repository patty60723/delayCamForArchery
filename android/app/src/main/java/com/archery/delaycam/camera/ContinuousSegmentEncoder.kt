package com.archery.delaycam.camera

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageProxy
import java.io.File
import java.nio.ByteBuffer

private const val TAG = "ContinuousSegmentEncoder"
private const val BIT_RATE = 8_000_000
private const val FRAME_RATE = 30
private const val I_FRAME_INTERVAL_SEC = 1
private const val DRAIN_TIMEOUT_US = 10_000L
private const val SEGMENT_TARGET_MS = 2000L

/**
 * Root-cause replacement for RollingSegmentRecorder's repeated CameraX
 * Recorder stop()/start() cycling. Confirmed on-device via logcat that
 * approach produced segments with genuinely inconsistent H.264 SPS
 * profile/level across segment boundaries ("Inconsisted profile/level
 * found in SPS: 64,0,1f vs ea,8f,2c") - each segment came from its own
 * independently-negotiated encoder session, and playback would stall or
 * error at exactly those boundaries. Widening the segment duration and
 * pinning the bitrate both only reduced how often it happened, not
 * whether it could happen at all.
 *
 * This class keeps exactly ONE MediaCodec encoder alive for the entire
 * LIVE session - it is never reconfigured or restarted - and only
 * rotates the *output file* at segment boundaries, cutting precisely on
 * a naturally-occurring keyframe (KEY_I_FRAME_INTERVAL is short, so one
 * is never far away) so every segment file is independently valid and
 * starts on a sync sample. Every segment therefore shares the exact same
 * SPS/PPS by construction, not by hoping the platform happens to
 * negotiate the same one twice.
 *
 * Public contract intentionally mirrors RollingSegmentRecorder's
 * (onSegmentFinalized: (SegmentInfo) -> Unit, setRingSize, the same
 * rollbuf_<n>.mp4 ring naming in the same cache dir) so none of the
 * downstream code that only ever dealt with SegmentInfo/File
 * (SegmentBuffer, DelayedPlaybackController, ClipExporter,
 * ContinuousRecordingSession) needed to change for this swap.
 *
 * FIRST ATTEMPT AT THIS FIX used a second CameraX Preview use case (its
 * SurfaceProvider handing frames to a Surface-mode MediaCodec input
 * surface) instead of the buffer-mode design below. CONFIRMED ON DEVICE
 * that failed outright: "No supported surface combination is found for
 * camera device - Id : 1" - this device's front camera does not support
 * two Preview-class streams alongside ImageAnalysis at once, so nothing
 * bound at all (no PiP preview, no segments, ever).
 *
 * This version instead reuses the ALREADY-bound ImageAnalysis stream
 * (the same one gesture detection already runs on) as the encoder's
 * frame source too - zero new camera streams, so it can't hit a new
 * surface-combination rejection: onCameraFrame(imageProxy) is called
 * from MainActivity's existing analyzer, and copies each YUV_420_888
 * frame's planes directly into a buffer-mode MediaCodec input Image
 * (KEY_COLOR_FORMAT = COLOR_FormatYUV420Flexible, MediaCodec.
 * getInputImage()) - no Surface, no GL, no lossy JPEG round-trip.
 * Rotation still can't be auto-corrected by the platform for a manual
 * feed like this, so the caller's imageInfo.rotationDegrees is baked
 * into each output file as MediaMuxer's orientation-hint metadata (the
 * same mechanism every phone camera app uses for portrait recordings).
 *
 * NOT YET VERIFIED ON DEVICE.
 */
class ContinuousSegmentEncoder(
    private val cacheDir: File,
    private val onSegmentFinalized: (SegmentInfo) -> Unit,
) {
    @Volatile private var ringSize: Int = 10
    @Volatile private var orientationHint: Int = 0
    @Volatile private var running = false

    private val mainHandler = Handler(Looper.getMainLooper())

    // Lifecycle fields (configure/stop) are touched from both the calling
    // analyzer thread (lazy configure on first frame) and whatever thread
    // calls stop() (ViewModel.onCleared, main thread) - synchronized.
    // Per-frame input calls (dequeueInputBuffer/queueInputBuffer) are not
    // synchronized against the drain loop's output calls: MediaCodec's own
    // contract explicitly supports one thread feeding input while another
    // drains output concurrently.
    private var codec: MediaCodec? = null
    private var drainThread: Thread? = null

    // Only ever touched from the single drain thread below once running.
    private var muxer: MediaMuxer? = null
    private var muxerTrackIndex = -1
    private var currentSegmentFile: File? = null
    private var segmentIndex = 0
    private var segmentStartedAtMs = 0L
    private var nextCutAtMs = 0L

    fun setRingSize(size: Int) {
        ringSize = size.coerceAtLeast(2)
    }

    /** Baked into each segment file as muxer orientation-hint metadata - see class doc. */
    fun setOrientationHint(degrees: Int) {
        orientationHint = degrees
    }

    /**
     * Call for every frame from the same ImageAnalysis.Analyzer gesture
     * detection already uses. Does NOT close imageProxy - the caller
     * (MainActivity) owns that, since GestureEngine.analyze() also needs
     * the same frame and already closes it when done with it.
     */
    fun onCameraFrame(imageProxy: ImageProxy) {
        setOrientationHint(imageProxy.imageInfo.rotationDegrees)
        configureIfNeeded(imageProxy.width, imageProxy.height)
        val c = codec ?: return

        val index = try {
            c.dequeueInputBuffer(0L)
        } catch (e: Exception) {
            Log.w(TAG, "dequeueInputBuffer failed, dropping this frame", e)
            return
        }
        if (index < 0) return // encoder busy - drop this frame, same spirit as STRATEGY_KEEP_ONLY_LATEST

        val inputImage = try {
            c.getInputImage(index)
        } catch (e: Exception) {
            Log.w(TAG, "getInputImage failed, dropping this frame", e)
            null
        }
        if (inputImage == null) {
            c.queueInputBuffer(index, 0, 0, 0, 0)
            return
        }
        try {
            copyYuvPlanes(imageProxy, inputImage)
            val ptsUs = imageProxy.imageInfo.timestamp / 1000L
            val frameSize = imageProxy.width * imageProxy.height * 3 / 2
            c.queueInputBuffer(index, 0, frameSize, ptsUs, 0)
        } catch (e: Exception) {
            Log.w(TAG, "copying frame into codec input failed, dropping this frame", e)
            c.queueInputBuffer(index, 0, 0, 0, 0)
        }
    }

    @Synchronized
    private fun configureIfNeeded(width: Int, height: Int) {
        if (codec != null) return
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
        }
        val newCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        newCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        newCodec.start()

        codec = newCodec
        running = true
        drainThread = Thread({ drainLoop() }, "ContinuousSegmentEncoder-drain").apply { start() }
    }

    @Synchronized
    fun stop() {
        if (codec == null) return
        running = false
        drainThread?.join(2000)
        drainThread = null
        try {
            codec?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "codec.stop() failed", e)
        }
        codec?.release()
        codec = null
    }

    private fun drainLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (running) {
            val c = codec ?: break
            val index = try {
                c.dequeueOutputBuffer(bufferInfo, DRAIN_TIMEOUT_US)
            } catch (e: Exception) {
                Log.w(TAG, "dequeueOutputBuffer failed, stopping drain loop", e)
                break
            }
            // index < 0 covers both INFO_TRY_AGAIN_LATER (nothing ready yet)
            // and the deprecated INFO_OUTPUT_BUFFERS_CHANGED - nothing to do
            // either way. INFO_OUTPUT_FORMAT_CHANGED just means
            // c.outputFormat is now queryable, which startNewSegment() reads
            // fresh each time it needs it, so no separate handling needed.
            if (index < 0) continue

            val buffer = c.getOutputBuffer(index)
            val isKeyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            val isCodecConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (buffer != null && bufferInfo.size > 0 && !isCodecConfig) {
                val now = System.currentTimeMillis()
                if (muxer == null) {
                    // Can't start a muxer without a sync sample as its first
                    // write - drop samples until the first keyframe, which
                    // for an H.264 encoder is always the very first frame.
                    if (isKeyFrame) startNewSegment(c, now)
                } else if (isKeyFrame && now >= nextCutAtMs) {
                    finalizeCurrentSegment(now)
                    startNewSegment(c, now)
                }
                muxer?.let { m ->
                    buffer.position(bufferInfo.offset)
                    buffer.limit(bufferInfo.offset + bufferInfo.size)
                    m.writeSampleData(muxerTrackIndex, buffer, bufferInfo)
                }
            }
            c.releaseOutputBuffer(index, false)
        }
        finalizeCurrentSegment(System.currentTimeMillis())
    }

    private fun startNewSegment(c: MediaCodec, startedAtMs: Long) {
        val file = segmentFile(segmentIndex)
        segmentIndex = (segmentIndex + 1) % ringSize
        val newMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        newMuxer.setOrientationHint(orientationHint)
        muxerTrackIndex = newMuxer.addTrack(c.outputFormat)
        newMuxer.start()
        muxer = newMuxer
        currentSegmentFile = file
        segmentStartedAtMs = startedAtMs
        nextCutAtMs = startedAtMs + SEGMENT_TARGET_MS
    }

    private fun finalizeCurrentSegment(endedAtMs: Long) {
        val m = muxer ?: return
        val file = currentSegmentFile
        muxer = null
        currentSegmentFile = null
        if (file == null) return
        try {
            m.stop()
        } catch (e: Exception) {
            // Can happen for a near-empty final segment on shutdown - not
            // worth surfacing as a save failure, just drop this one segment.
            Log.w(TAG, "muxer.stop() failed for $file, dropping this segment", e)
            return
        }
        m.release()
        val info = SegmentInfo(file, segmentStartedAtMs, endedAtMs)
        // onSegmentFinalized ultimately touches livePlayer (an ExoPlayer,
        // confined to the thread that created it - see DelayCamViewModel's
        // GestureEngine callback fix for the exact crash this avoids), but
        // this runs on the drain thread, not main - must hop back.
        mainHandler.post { onSegmentFinalized(info) }
    }

    /** rollbuf_<index>.mp4 - overwritten in place each time its slot comes back around. */
    private fun segmentFile(index: Int): File = File(cacheDir, "rollbuf_segment_$index.mp4")
}

/**
 * Copies a YUV_420_888 ImageProxy's three planes into a MediaCodec buffer-
 * mode input Image (also YUV_420_888-shaped for COLOR_FormatYUV420Flexible).
 * Row-stride and pixel-stride can both differ between the camera's planes
 * and the codec's expected input planes, so this is a defensive row-by-row
 * copy rather than a flat buffer copy - the standard pattern for feeding
 * camera frames into MediaCodec's buffer-mode Image input.
 */
private fun copyYuvPlanes(src: ImageProxy, dst: Image) {
    val srcPlanes = src.planes
    val dstPlanes = dst.planes
    copyPlane(srcPlanes[0].buffer, srcPlanes[0].rowStride, srcPlanes[0].pixelStride,
        dstPlanes[0].buffer, dstPlanes[0].rowStride, dstPlanes[0].pixelStride,
        src.width, src.height)
    val chromaWidth = src.width / 2
    val chromaHeight = src.height / 2
    copyPlane(srcPlanes[1].buffer, srcPlanes[1].rowStride, srcPlanes[1].pixelStride,
        dstPlanes[1].buffer, dstPlanes[1].rowStride, dstPlanes[1].pixelStride,
        chromaWidth, chromaHeight)
    copyPlane(srcPlanes[2].buffer, srcPlanes[2].rowStride, srcPlanes[2].pixelStride,
        dstPlanes[2].buffer, dstPlanes[2].rowStride, dstPlanes[2].pixelStride,
        chromaWidth, chromaHeight)
}

private fun copyPlane(
    srcBuf: ByteBuffer,
    srcRowStride: Int,
    srcPixelStride: Int,
    dstBuf: ByteBuffer,
    dstRowStride: Int,
    dstPixelStride: Int,
    width: Int,
    height: Int,
) {
    val src = srcBuf.duplicate()
    val dst = dstBuf.duplicate()
    if (srcPixelStride == 1 && dstPixelStride == 1) {
        val row = ByteArray(width)
        for (r in 0 until height) {
            src.position(r * srcRowStride)
            src.get(row, 0, width)
            dst.position(r * dstRowStride)
            dst.put(row, 0, width)
        }
    } else {
        val srcRow = ByteArray(srcRowStride)
        val dstRow = ByteArray(width * dstPixelStride)
        for (r in 0 until height) {
            src.position(r * srcRowStride)
            val available = minOf(srcRowStride, src.remaining())
            src.get(srcRow, 0, available)
            var s = 0
            var d = 0
            for (col in 0 until width) {
                dstRow[d] = srcRow[s]
                s += srcPixelStride
                d += dstPixelStride
            }
            dst.position(r * dstRowStride)
            dst.put(dstRow, 0, d)
        }
    }
}
