package com.archery.delaycam.camera

import android.content.Context
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
 * NOT YET VERIFIED ON DEVICE. Concatenating MediaMuxer output from several
 * independently-started Recorder sessions is the riskiest part of this
 * skeleton: each segment's own encoder session may emit its own codec
 * config (SPS/PPS) and the first frame of a segment may not be a keyframe
 * if the stop()/start() boundary lands awkwardly. If exported clips glitch
 * at segment joins, that's the first place to look - a more robust (but
 * slower) fallback is decoding + re-encoding through MediaCodec instead of
 * raw sample copying. (ContinuousRecordingSession does the same kind of
 * concatenation incrementally, for the same reason and with the same caveat.)
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
            concatenate(segments.map { it.file }, tempFile)
            MediaStoreExport.saveVideoToMediaStore(context, tempFile, displayName)
        } finally {
            tempFile.delete()
        }
    }

    private fun concatenate(inputs: List<File>, output: File) {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val bufferInfo = MediaCodec.BufferInfo()
        val buffer = ByteBuffer.allocate(1 shl 20) // 1MB, generous for one segment's worth of samples

        var videoTrackIndex = -1
        var muxerStarted = false
        var ptsOffsetUs = 0L

        for (input in inputs) {
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(input.absolutePath)
                val trackIndex = MediaStoreExport.selectVideoTrack(extractor) ?: continue
                extractor.selectTrack(trackIndex)

                if (!muxerStarted) {
                    videoTrackIndex = muxer.addTrack(extractor.getTrackFormat(trackIndex))
                    muxer.start()
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
                    muxer.writeSampleData(videoTrackIndex, buffer, bufferInfo)
                    extractor.advance()
                }
                // next segment's samples continue after this one's last timestamp
                ptsOffsetUs = maxPtsUs + 1
            } finally {
                extractor.release()
            }
        }

        if (!muxerStarted) throw IOException("none of the segments had a readable video track")
        muxer.stop()
        muxer.release()
    }
}
