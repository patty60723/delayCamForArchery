package com.archery.delaycam.camera

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
 * Frames arrive via a CameraX Preview.SurfaceProvider handing camera
 * frames to this class's own input Surface (see prepare()), wired up in
 * MainActivity - unlike PreviewView/CameraX's own Recorder, CameraX does
 * NOT auto-correct rotation for a custom SurfaceProvider, so the caller
 * must forward it from the SurfaceRequest's TransformationInfoListener
 * via setOrientationHint(). That's baked into each output file as
 * MediaMuxer's orientation-hint metadata (the same mechanism every phone
 * camera app uses for portrait recordings) rather than by transforming
 * pixels - simpler, and avoids needing a GL rendering pass just to
 * rotate frames before they reach the encoder.
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

    /** Forwarded from SurfaceRequest.TransformationInfoListener - see class doc. */
    fun setOrientationHint(degrees: Int) {
        orientationHint = degrees
    }

    /**
     * Idempotent: returns the already-prepared surface if called again.
     * Must be called with the camera's actual negotiated resolution
     * (SurfaceRequest.resolution), not a value this class picks itself -
     * the encoder's input surface size is fixed at configure() time and
     * must match what the camera will actually produce.
     */
    @Synchronized
    fun prepare(width: Int, height: Int): Surface {
        inputSurface?.let { return it }

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
        return surface
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
