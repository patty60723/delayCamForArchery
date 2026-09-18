package com.archery.delaycam.camera

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * "Just film the whole session" alternative to gesturing/pressing save for
 * every single arrow - added on user feedback, ported from the web
 * prototype's continuousRecorder.js sibling.
 *
 * Binding a second, concurrent CameraX VideoCapture use case just for this
 * would be unreliable across devices (Preview + VideoCapture + ImageAnalysis
 * is already close to what most devices guarantee), so instead of a separate
 * recording pipeline, this taps the SAME rolling-buffer segments
 * RollingSegmentRecorder already produces: it keeps a MediaMuxer open across
 * the whole recording and appends each new segment's samples as they finalize
 * (DelayCamViewModel wires this up), finalizing the muxer only when the user
 * stops. This is the same sample-copying approach ClipExporter uses for a
 * fixed, already-known list of segments, just done incrementally - see that
 * class's doc comment for the concatenation caveats, which apply here too.
 *
 * One consequence of reusing segments: whatever fraction of the
 * currently-recording segment hasn't finalized yet when the user hits stop
 * isn't included, so the saved file can be up to ~1s short of the exact
 * button-press moment. Acceptable for a "record the whole session" feature.
 *
 * NOT YET VERIFIED ON DEVICE - see android/README.md.
 */
class ContinuousRecordingSession(private val context: Context) {

    private val mutex = Mutex()
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var videoTrackIndex = -1
    private var ptsOffsetUs = 0L
    private var tempFile: File? = null
    @Volatile private var startedAtMs = 0L

    val isRecording: Boolean get() = muxer != null
    val elapsedMs: Long get() = if (isRecording) System.currentTimeMillis() - startedAtMs else 0L

    suspend fun start() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (muxer != null) return@withLock
            val file = File.createTempFile("archery_session", ".mp4", context.cacheDir)
            tempFile = file
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxerStarted = false
            videoTrackIndex = -1
            ptsOffsetUs = 0L
            startedAtMs = System.currentTimeMillis()
        }
    }

    /** Call for every segment RollingSegmentRecorder finalizes while recording is active. */
    suspend fun appendSegment(segment: SegmentInfo) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val activeMuxer = muxer ?: return@withLock
            val extractor = MediaExtractor()
            val bufferInfo = MediaCodec.BufferInfo()
            val buffer = ByteBuffer.allocate(1 shl 20)
            try {
                extractor.setDataSource(segment.file.absolutePath)
                val trackIndex = MediaStoreExport.selectVideoTrack(extractor) ?: return@withLock
                extractor.selectTrack(trackIndex)

                if (!muxerStarted) {
                    videoTrackIndex = activeMuxer.addTrack(extractor.getTrackFormat(trackIndex))
                    activeMuxer.start()
                    muxerStarted = true
                }

                var maxPtsUs = 0L
                while (true) {
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = extractor.sampleTime + ptsOffsetUs
                    bufferInfo.flags = extractor.sampleFlags
                    maxPtsUs = maxOf(maxPtsUs, bufferInfo.presentationTimeUs)
                    activeMuxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                    extractor.advance()
                }
                ptsOffsetUs = maxPtsUs + 1
            } catch (e: Exception) {
                // one bad segment shouldn't kill the whole session recording
            } finally {
                extractor.release()
            }
        }
    }

    /** Finalizes the muxer and saves the result to MediaStore. */
    suspend fun stop(displayName: String): Uri = withContext(Dispatchers.IO) {
        mutex.withLock {
            val activeMuxer = muxer ?: throw IllegalStateException("not recording")
            val file = tempFile
            muxer = null
            tempFile = null
            try {
                if (muxerStarted) activeMuxer.stop()
                activeMuxer.release()
                if (!muxerStarted || file == null) {
                    throw IOException("no footage was captured during this recording")
                }
                MediaStoreExport.saveVideoToMediaStore(context, file, displayName)
            } finally {
                file?.delete()
            }
        }
    }

    /** Best-effort cleanup if the app is torn down mid-recording - no save, just release resources. */
    fun releaseImmediate() {
        try {
            muxer?.release()
        } catch (e: Exception) {
            // already released or never started - nothing to do
        }
        muxer = null
        tempFile?.delete()
        tempFile = null
    }
}
