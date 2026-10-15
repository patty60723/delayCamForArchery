package com.archery.delaycam.camera

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
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
 * would be unreliable across devices (Preview + Preview + ImageAnalysis is
 * already close to what most devices guarantee), so instead of a separate
 * recording pipeline, this taps the SAME rolling-buffer segments
 * ContinuousSegmentEncoder already produces: it keeps a MediaMuxer open across
 * the whole recording and appends each new segment's samples as they finalize
 * (DelayCamViewModel wires this up), finalizing the muxer only when the user
 * stops. This is the same sample-copying approach ClipExporter uses for a
 * fixed, already-known list of segments, just done incrementally - see that
 * class's doc comment for the concatenation caveats, which apply here too.
 *
 * The ViewModel serializes start/append/stop commands. Stop waits for the
 * containing segment or encoder shutdown before finalization, then exports
 * the resulting file separately so a failed MediaStore copy can be retried.
 * System process-death recovery is not implemented.
 */
private const val TAG = "ContinuousRecordingSession"

class ContinuousRecordingSession(private val context: Context) {

    private val mutex = Mutex()
    private var muxer: MediaMuxer? = null
    private var muxerStarted = false
    private var videoTrackIndex = -1
    private var timeline = SegmentTimeline()
    private var tempFile: File? = null
    private val ranges = mutableListOf<com.archery.delaycam.gallery.PictureRange>()
    var outputAspect: Float? = null
        private set
    fun presentationRanges(): List<com.archery.delaycam.gallery.PictureRange> = ranges.toList()

    val isRecording: Boolean get() = muxer != null

    suspend fun start(aspect: Float? = null) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (muxer != null) return@withLock
            val file = File.createTempFile("archery_session", ".mp4", context.cacheDir)
            try {
                muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                tempFile = file
            } catch (e: Exception) {
                file.delete()
                throw e
            }
            muxerStarted = false
            videoTrackIndex = -1
            timeline = SegmentTimeline()
            ranges.clear()
            outputAspect = aspect
        }
    }

    /** Call for every segment ContinuousSegmentEncoder finalizes while recording is active. */
    suspend fun appendSegment(segment: SegmentInfo) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val activeMuxer = muxer ?: return@withLock
            val extractor = MediaExtractor()
            val bufferInfo = MediaCodec.BufferInfo()
            val buffer = ByteBuffer.allocate(1 shl 20)
            try {
                extractor.setDataSource(segment.file.absolutePath)
                val trackIndex = MediaStoreExport.selectVideoTrack(extractor)
                    ?: throw IOException("錄影片段沒有可讀取的影像")
                extractor.selectTrack(trackIndex)
                if (outputAspect == null) {
                    val format = extractor.getTrackFormat(trackIndex)
                    outputAspect = segment.contentAspectRatio ?: (
                        format.getInteger(android.media.MediaFormat.KEY_WIDTH).toFloat() /
                            format.getInteger(android.media.MediaFormat.KEY_HEIGHT))
                }

                if (!muxerStarted) {
                    videoTrackIndex = activeMuxer.addTrack(extractor.getTrackFormat(trackIndex))
                    activeMuxer.start()
                    muxerStarted = true
                }

                timeline.beginSegment()
                while (true) {
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) break
                    bufferInfo.offset = 0
                    bufferInfo.size = sampleSize
                    bufferInfo.presentationTimeUs = timeline.map(extractor.sampleTime)
                    bufferInfo.flags = extractor.sampleFlags
                    activeMuxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                    extractor.advance()
                }
                timeline.endSegment()
                ranges.add(com.archery.delaycam.gallery.PictureRange(timeline.durationUs / 1000L, segment.contentAspectRatio))
            } finally {
                extractor.release()
            }
        }
    }

    /** Finalizes to a file; the caller retains it until MediaStore export succeeds. */
    suspend fun stopToFile(): File = withContext(Dispatchers.IO) {
        mutex.withLock {
            val activeMuxer = muxer ?: throw IllegalStateException("not recording")
            val file = tempFile
            muxer = null
            tempFile = null
            try {
                if (muxerStarted) activeMuxer.stop()
                if (!muxerStarted || file == null) {
                    throw IOException("no footage was captured during this recording")
                }
                file
            } finally {
                activeMuxer.release()
            }
        }
    }

    /** Serialized cleanup, after any in-flight muxer operation has completed. */
    suspend fun releaseResources() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try { muxer?.release() } catch (_: Exception) { }
            muxer = null
            tempFile?.delete()
            tempFile = null
        }
    }
}
