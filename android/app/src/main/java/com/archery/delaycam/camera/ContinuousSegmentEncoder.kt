package com.archery.delaycam.camera

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import java.io.File

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
 * error at exactly those boundaries.
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
 * TWO EARLIER ATTEMPTS at feeding this class camera frames both failed
 * on-device:
 *  1. A second CameraX Preview use case handing frames to a Surface-mode
 *     encoder input surface. Failed outright at bind time: "No supported
 *     surface combination is found for camera device" - this device's
 *     front camera doesn't support two Preview-class streams alongside
 *     ImageAnalysis, so nothing bound at all.
 *  2. Reusing the existing ImageAnalysis stream, but feeding the encoder
 *     in buffer-input mode via a hand-rolled direct YUV-plane-to-
 *     MediaCodec-Image copy. Bound fine, but every single frame threw
 *     BufferOverflowException in the copy - never debugged further since
 *     a safer alternative was available (see below), but the fact that it
 *     failed identically on the very first frame suggests a systematic
 *     stride/size miscalculation rather than a rare edge case.
 *
 * This version instead reuses the already-bound ImageAnalysis stream (the
 * same one gesture detection runs on, so zero new camera streams - same
 * as attempt 2) but goes back to Surface-mode encoding (COLOR_FormatSurface,
 * createInputSurface()) like attempt 1, feeding it via onFrame(bitmap):
 * MainActivity converts each ImageProxy to an upright Bitmap exactly once
 * per frame using the same conversion GestureEngine already uses
 * successfully for gesture recognition (camera/ImageProxyConversion.kt),
 * and this class draws that Bitmap onto the encoder's Surface via
 * Surface.lockHardwareCanvas()/Canvas.drawBitmap() - both very standard,
 * well-documented APIs, avoiding the hand-rolled buffer math that failed
 * in attempt 2. Because the shared conversion already rotates the image
 * to upright, no separate orientation-hint step is needed here (unlike
 * the two earlier attempts) - the pixels themselves are already correct.
 *
 * Trade-off accepted for now: the shared conversion round-trips through a
 * lossy JPEG encode/decode (see ImageProxyConversion.kt), so recorded
 * video quality is somewhat below what direct YUV encoding would give,
 * and the conversion's CPU cost eats into the analyzer's per-frame budget
 * (ImageAnalysis's STRATEGY_KEEP_ONLY_LATEST will drop frames it can't
 * keep up with). Worth revisiting once the pipeline is confirmed to
 * actually work at all.
 *
 * Confirmed on-device that this version does eliminate the SPS/PPS
 * mismatch that plagued the old Recorder-based approach - zero
 * occurrences in a full test session that previously saw one every
 * reset. A small number of stalls (caught and recovered by
 * DelayCamViewModel's watchdog/error-listener) still happened, at a much
 * lower rate. A closer pass over this class for that found two real
 * robustness gaps unrelated to SPS drift, now fixed: drainLoop()'s
 * per-frame write/cut logic had no exception handling at all, so a
 * transient MediaMuxer failure (disk hiccup, addTrack()/start()
 * throwing) would crash this whole background thread - and, since it's
 * uncaught, the app - instead of just losing one segment; and a failed
 * muxer.stop() (already caught) skipped release(), leaking that muxer's
 * native resources every time it happened. Neither is confirmed as the
 * cause of the remaining rare stalls, but both were real bugs regardless
 * and are the most likely remaining candidates.
 *
 * NOT YET VERIFIED ON DEVICE (the fixes in the paragraph above).
 */
class ContinuousSegmentEncoder(
    private val cacheDir: File,
    private val onSegmentFinalized: (SegmentInfo) -> Unit,
) {
    @Volatile private var ringSize: Int = 10
    @Volatile private var running = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
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

    /**
     * Call for every frame from the same ImageAnalysis.Analyzer gesture
     * detection already uses, already converted to an upright Bitmap by
     * the caller (see class doc). Lazily configures the encoder on the
     * first call using that bitmap's dimensions.
     */
    fun onFrame(bitmap: Bitmap) {
        configureIfNeeded(bitmap.width, bitmap.height)
        val surface = inputSurface ?: return
        val canvas = try {
            surface.lockHardwareCanvas()
        } catch (e: Exception) {
            Log.w(TAG, "lockHardwareCanvas failed, dropping this frame", e)
            return
        }
        try {
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    @Synchronized
    private fun configureIfNeeded(width: Int, height: Int) {
        if (codec != null) return
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SEC)
        }
        val newCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        newCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = newCodec.createInputSurface()
        newCodec.start()

        codec = newCodec
        inputSurface = surface
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
        inputSurface = null
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

            // Found on a closer pass while digging into the occasional
            // still-remaining stall: this whole block used to run with no
            // try/catch at all. A transient MediaMuxer failure here (disk
            // hiccup, addTrack/start() throwing) would propagate uncaught
            // out of this background thread - which crashes the whole app,
            // not just this one segment. Catching it and dropping just the
            // current segment (the next keyframe starts a fresh one, same
            // as the normal bootstrap path) keeps a rare native hiccup from
            // taking down the delayed view - or the app - entirely.
            try {
                val buffer = c.getOutputBuffer(index)
                val isKeyFrame = bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                val isCodecConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                if (buffer != null && bufferInfo.size > 0 && !isCodecConfig) {
                    val now = System.currentTimeMillis()
                    if (muxer == null) {
                        // Can't start a muxer without a sync sample as its
                        // first write - drop samples until the first
                        // keyframe, which for an H.264 encoder is always
                        // the very first frame.
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
            } catch (e: Exception) {
                Log.w(TAG, "segment write/cut failed, dropping current segment and continuing", e)
                muxer?.let { m -> try { m.release() } catch (_: Exception) {} }
                muxer = null
                currentSegmentFile = null
            } finally {
                c.releaseOutputBuffer(index, false)
            }
        }
        finalizeCurrentSegment(System.currentTimeMillis())
    }

    private fun startNewSegment(c: MediaCodec, startedAtMs: Long) {
        val file = segmentFile(segmentIndex)
        segmentIndex = (segmentIndex + 1) % ringSize
        val newMuxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        // No orientation hint needed: the Bitmap onFrame() draws is already
        // upright (see class doc), unlike the two earlier attempts which
        // fed the encoder raw sensor-orientation frames.
        try {
            muxerTrackIndex = newMuxer.addTrack(c.outputFormat)
            newMuxer.start()
        } catch (e: Exception) {
            // addTrack/start() throwing would otherwise leak newMuxer's
            // native resources - it never got assigned to the `muxer`
            // field, so nothing else would ever release it.
            try { newMuxer.release() } catch (_: Exception) {}
            throw e
        }
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
            // worth surfacing as a save failure, just drop this one
            // segment. Still release() it though - this used to skip that,
            // leaking the muxer's native resources on every occurrence
            // (fd/encoder-adjacent handles that would only add up over a
            // long session, but never got cleaned up).
            Log.w(TAG, "muxer.stop() failed for $file, dropping this segment", e)
            try { m.release() } catch (_: Exception) {}
            return
        }
        m.release()
        val info = SegmentInfo(file, segmentStartedAtMs, endedAtMs)
        // Diagnostic for the still-remaining periodic stalls (~1 in 7
        // segments, no clear signal on which one or why): logging every
        // segment's actual duration and file size. A degenerate segment
        // (near-zero duration or suspiciously small size compared to its
        // neighbors) landing in the delayed view's playlist would be a
        // concrete, checkable explanation for ExoPlayer getting stuck
        // transitioning into it - this makes that visible instead of
        // guessed at.
        Log.d(TAG, "segment finalized: ${file.name} duration=${endedAtMs - segmentStartedAtMs}ms size=${file.length()}B")
        // onSegmentFinalized ultimately touches livePlayer (an ExoPlayer,
        // confined to the thread that created it - see DelayCamViewModel's
        // GestureEngine callback fix for the exact crash this avoids), but
        // this runs on the drain thread, not main - must hop back.
        mainHandler.post { onSegmentFinalized(info) }
    }

    /** rollbuf_<index>.mp4 - overwritten in place each time its slot comes back around. */
    private fun segmentFile(index: Int): File = File(cacheDir, "rollbuf_segment_$index.mp4")
}
