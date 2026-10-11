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
}
