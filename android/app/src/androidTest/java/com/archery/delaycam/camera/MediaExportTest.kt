package com.archery.delaycam.camera

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Native extractor/muxer/MediaStore checks using a generated one-second H.264 fixture. */
class MediaExportTest {
    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun fixture(): File {
        val file = File.createTempFile("media_fixture", ".mp4", context.cacheDir)
        InstrumentationRegistry.getInstrumentation().context.assets.open("clip-fixture.mp4").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    private fun assertPlayableTwoSeconds(configure: (MediaMetadataRetriever) -> Unit) {
        val retriever = MediaMetadataRetriever()
        try {
            configure(retriever)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertTrue("Expected two seconds, got $duration ms", duration in 1_800L..2_200L)
            val frame = retriever.getFrameAtTime(1_500_000L, MediaMetadataRetriever.OPTION_CLOSEST)
            assertNotNull("Second segment must decode", frame)
            frame?.recycle()
        } finally { retriever.release() }
    }

    @Test fun shotExportJoinsSegmentsAndProducesPlayableMediaStoreFile() = runBlocking {
        val input = fixture()
        var uri: android.net.Uri? = null
        try {
            val segments = listOf(SegmentInfo(input, 0, 1_000), SegmentInfo(input, 1_000, 2_000))
            val exported = ClipExporter.export(context, segments, "test_shot_" + UUID.randomUUID() + ".mp4")
            uri = exported
            assertPlayableTwoSeconds { it.setDataSource(context, exported) }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, exported, null)
                extractor.selectTrack(MediaStoreExport.selectVideoTrack(extractor)!!)
                var previous = -1L
                var samples = 0
                while (extractor.sampleTime >= 0) {
                    assertTrue(extractor.sampleTime > previous)
                    previous = extractor.sampleTime
                    samples++
                    extractor.advance()
                }
                assertEquals(20, samples)
            } finally { extractor.release() }
        } finally {
            uri?.let { context.contentResolver.delete(it, null, null) }
            input.delete()
        }
    }

    @Test fun fullRecordingFinalizesPlayableFile() = runBlocking {
        val input = fixture()
        val recording = ContinuousRecordingSession(context)
        var output: File? = null
        try {
            recording.start()
            recording.appendSegment(SegmentInfo(input, 0, 1_000))
            recording.appendSegment(SegmentInfo(input, 1_000, 2_000))
            val completed = recording.stopToFile()
            output = completed
            assertFalse(recording.isRecording)
            assertPlayableTwoSeconds { it.setDataSource(completed.absolutePath) }
        } finally {
            recording.releaseResources()
            output?.delete()
            input.delete()
        }
    }

    @Test fun replaySnapshotSurvivesRingSlotOverwrite() = runBlocking {
        val input = fixture()
        val original = input.readBytes()
        val snapshot = ClipSnapshot.copy(context.cacheDir, listOf(SegmentInfo(input, 0, 1_000)))
        try {
            input.writeText("overwritten")
            assertArrayEquals(original, snapshot.segments.single().file.readBytes())
        } finally {
            snapshot.delete()
            input.delete()
        }
        assertFalse(snapshot.segments.single().file.exists())
    }

    @Test fun failedCopyDoesNotLeavePendingGalleryEntry() {
        val name = "test_failed_" + UUID.randomUUID() + ".mp4"
        val missing = File(context.cacheDir, UUID.randomUUID().toString())
        try {
            MediaStoreExport.saveVideoToMediaStore(context, missing, name)
            fail("Missing source must fail")
        } catch (_: java.io.IOException) { }
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI.buildUpon()
            .appendQueryParameter("includePending", "1").build()
        context.contentResolver.query(collection, arrayOf(MediaStore.Video.Media._ID),
            MediaStore.Video.Media.DISPLAY_NAME + " = ?", arrayOf(name), null).use {
            assertNotNull(it)
            assertEquals(0, it!!.count)
        }
    }

    @Test fun realEncoderPublishesImmutablePlayableSegmentsWithUsableSourceTiming() {
        val store = ImmutableSegmentStore(context.cacheDir, "encoder_test_${UUID.randomUUID()}")
        val segments = java.util.Collections.synchronizedList(mutableListOf<SegmentInfo>())
        val leases = java.util.Collections.synchronizedList(mutableListOf<ImmutableSegmentStore.Lease>())
        val stopped = java.util.concurrent.CountDownLatch(1)
        val encoder = ContinuousSegmentEncoder(context.cacheDir, { segment ->
            store.acquire(listOf(segment))?.let { leases.add(it); segments.add(segment) }
        }, { stopped.countDown() }, immutableStore = store)
        val bitmap = android.graphics.Bitmap.createBitmap(320, 240, android.graphics.Bitmap.Config.ARGB_8888)
        val portrait = android.graphics.Bitmap.createBitmap(240, 320, android.graphics.Bitmap.Config.ARGB_8888)
        encoder.setRingSize(2)
        try {
            val deadline = android.os.SystemClock.elapsedRealtime() + 7_000
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                bitmap.eraseColor(android.graphics.Color.GREEN)
                portrait.eraseColor(android.graphics.Color.RED)
                encoder.onFrame(if (deadline - android.os.SystemClock.elapsedRealtime() > 3_500) bitmap else portrait)
                android.os.SystemClock.sleep(50)
            }
            encoder.stop()
            assertTrue(stopped.await(10, java.util.concurrent.TimeUnit.SECONDS))
            val completed = segments.toList()
            assertTrue("Need multiple keyframe-bounded files", completed.size >= 3)
            assertEquals(completed.size, completed.map { it.file }.distinct().size)
            assertTrue("Source/media timing must support fast seek", completed.dropLast(1).any { FastResumePlan.valid(it) })
            completed.forEach { segment ->
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(segment.file.absolutePath)
                    assertEquals("320", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                    assertEquals("240", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                    val actualDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                    assertTrue("MP4 duration=$actualDuration must match seek duration=${segment.mediaDurationMs}",
                        kotlin.math.abs(actualDuration - segment.mediaDurationMs) <= 200L)
                    val frame = retriever.getFrameAtTime(0)
                    assertNotNull(frame)
                    frame?.recycle()
                } finally { retriever.release() }
            }
        } finally {
            encoder.stop()
            bitmap.recycle()
            portrait.recycle()
            leases.forEach { it.close() }
            store.close()
        }
    }
}
