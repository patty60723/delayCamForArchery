package com.archery.delaycam.camera

import android.content.Context
import com.archery.delaycam.gallery.ClipPresentationStore
import com.archery.delaycam.gallery.PictureRange
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Backtrack save (spec 2.2 step 1 / 6): concatenates the rolling-buffer
 * segments covering the backtrack window into one permanent MP4, saved via
 * MediaStore under Movies/Archery so it shows up in the system gallery,
 * separate from the private-cache rolling segments (spec 6).
 *
 * Inputs are protected snapshots from one foreground encoder session.
 * SegmentTimeline normalizes input timestamps and joins the video samples.
 * Real camera output and exported files still require device verification.
 */
object ClipExporter {

    suspend fun export(
        context: Context,
        segments: List<SegmentInfo>,
        displayName: String,
    ): Uri = withContext(Dispatchers.IO) {
        require(segments.isNotEmpty()) { "no segments to export" }
        val tempFile = File.createTempFile("archery_export", ".mp4", context.cacheDir)
        try {
            val ranges = concatenate(segments, tempFile)
            val normalized = VideoAspectNormalizer.normalize(context, tempFile, ranges)
            try {
                val uri = MediaStoreExport.saveVideoToMediaStore(context, normalized.file, displayName)
                try { ClipPresentationStore(context).write(uri, normalized.ranges) }
                catch (failure: Exception) { android.util.Log.w("ClipExporter", "Presentation metadata unavailable", failure) }
                uri
            } finally { if (normalized.file != tempFile) normalized.file.delete() }
        } finally {
            tempFile.delete()
        }
    }

    private fun concatenate(inputs: List<SegmentInfo>, output: File): List<PictureRange> {
        val ranges = mutableListOf<PictureRange>()
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val bufferInfo = MediaCodec.BufferInfo()
        val buffer = ByteBuffer.allocate(1 shl 20) // 1MB, generous for one segment's worth of samples

        var videoTrackIndex = -1
        var muxerStarted = false
        val timeline = SegmentTimeline()

        try {
        for (input in inputs) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(input.file.absolutePath)
                val trackIndex = MediaStoreExport.selectVideoTrack(extractor)
                    ?: throw IOException("影片片段沒有可讀取的影像")
                extractor.selectTrack(trackIndex)

                if (!muxerStarted) {
                    videoTrackIndex = muxer.addTrack(extractor.getTrackFormat(trackIndex))
                    muxer.start()
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
                    muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                    extractor.advance()
                }
                // next segment's samples continue after this one's last timestamp
                timeline.endSegment()
                ranges.add(PictureRange(timeline.durationUs / 1000L, input.contentAspectRatio))
            } finally {
                extractor.release()
            }
        }

        if (!muxerStarted) throw IOException("none of the segments had a readable video track")
        muxer.stop()
        } finally {
            muxer.release()
        }
        return ranges
    }
}

