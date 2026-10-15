package com.archery.delaycam.camera

import android.media.MediaMetadataRetriever
import androidx.test.platform.app.InstrumentationRegistry
import com.archery.delaycam.gallery.PictureRange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VideoAspectExportTest {
    @Test(timeout = 30_000) fun recordingStartedBeforeFramesUsesFirstActualCaptureOrientation() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File.createTempFile("first_capture", ".mp4", context.cacheDir)
        instrumentation.context.assets.open("clip-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        val recording = ContinuousRecordingSession(context)
        var completed: File? = null
        try {
            recording.start() // No camera frame yet: portrait/landscape is not known.
            assertNull(recording.outputAspect)
            recording.appendSegment(SegmentInfo(source, 0, 1000, 1000, 16f / 9f))
            assertEquals(16f / 9f, recording.outputAspect!!, .001f)
            recording.appendSegment(SegmentInfo(source, 1000, 2000, 1000, 9f / 16f))
            assertEquals("Later rotation must keep the starting format", 16f / 9f, recording.outputAspect!!, .001f)
            completed = recording.stopToFile()
        } finally { completed?.delete(); recording.releaseResources(); source.delete() }
    }

    @Test(timeout = 30_000) fun matchingFormatKeepsOriginalFileBytesAndMixedPresentationTimeline() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File.createTempFile("same_aspect", ".mp4", context.cacheDir)
        instrumentation.context.assets.open("clip-fixture.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        try {
            val before = source.readBytes()
            val retriever = MediaMetadataRetriever()
            val aspect = try {
                retriever.setDataSource(source.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toFloat() /
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)!!.toFloat()
            } finally { retriever.release() }
            val ranges = listOf(PictureRange(500, aspect), PictureRange(1000, null))
            val result = VideoAspectNormalizer.normalize(context, source, ranges, aspect)
            assertSame(source, result.file)
            assertEquals(ranges, result.ranges)
            assertArrayEquals(before, result.file.readBytes())
        } finally { source.delete() }
    }

    @Test(timeout = 120_000) fun paddedLandscapeIsExportedAsRealLandscapeAndMixedSourceRemainsVisible() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File.createTempFile("aspect_fixture", ".mp4", context.cacheDir)
        instrumentation.context.assets.open("portrait-padded-landscape.mp4").use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        try {
            val ranges = listOf(PictureRange(2000, 4f / 3f))
            val result = VideoAspectNormalizer.normalize(context, source, ranges)
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(result.file.absolutePath)
                    val frame = retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
                    try {
                        assertEquals(4f / 3f, frame.width.toFloat() / frame.height, .02f)
                        assertTrue("Content must fill the output; encoder padding is removed", android.graphics.Color.green(frame.getPixel(frame.width / 2, frame.height / 2)) > 160)
                        assertTrue("No second letterbox at the left edge", android.graphics.Color.green(frame.getPixel(3, frame.height / 2)) > 160)
                        assertTrue("No second letterbox at the right edge", android.graphics.Color.green(frame.getPixel(frame.width - 4, frame.height / 2)) > 160)
                        // The source fixture has yellow/cyan boundary strips. FIT must preserve them.
                        val top = frame.getPixel(frame.width / 2, 3)
                        val bottom = frame.getPixel(frame.width / 2, frame.height - 4)
                        assertTrue(android.graphics.Color.red(top) > 150 && android.graphics.Color.green(top) > 150)
                        assertTrue(android.graphics.Color.green(bottom) > 150 && android.graphics.Color.blue(bottom) > 150)
                    } finally { frame.recycle() }
                } finally { retriever.release() }
            } finally { if (result.file != source) result.file.delete() }
            // Lock a portrait output while the source has landscape and unknown/mixed ranges.
            val mixed = VideoAspectNormalizer.normalize(context, source,
                listOf(PictureRange(1000, 4f / 3f), PictureRange(2000, null)), 9f / 16f)
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(mixed.file.absolutePath)
                    val frame = retriever.getFrameAtTime(1_500_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
                    try { assertEquals(9f / 16f, frame.width.toFloat() / frame.height, .02f) }
                    finally { frame.recycle() }
                } finally { retriever.release() }
            } finally { if (mixed.file != source) mixed.file.delete() }
        } finally { source.delete() }
    }

    @Test(timeout = 120_000) fun realEncoderCaptureAndExportHaveSixteenNinePixels() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val segments = java.util.Collections.synchronizedList(mutableListOf<SegmentInfo>())
        val stopped = java.util.concurrent.CountDownLatch(1)
        val prefix = "wide_test_${System.nanoTime()}"
        val encoder = ContinuousSegmentEncoder(context.cacheDir, { segments.add(it) }, { stopped.countDown() }, filePrefix = prefix)
        val bitmap = android.graphics.Bitmap.createBitmap(320, 240, android.graphics.Bitmap.Config.ARGB_8888)
        var uri: android.net.Uri? = null
        try {
            bitmap.eraseColor(android.graphics.Color.GREEN)
            val until = android.os.SystemClock.elapsedRealtime() + 3500
            while (android.os.SystemClock.elapsedRealtime() < until) { encoder.onFrame(bitmap, 16f / 9f); android.os.SystemClock.sleep(50) }
            encoder.stop()
            assertTrue(stopped.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(segments.isNotEmpty())
            assertTrue(segments.all { it.contentAspectRatio == 16f / 9f })
            uri = ClipExporter.export(context, segments.toList(), "$prefix.mp4")
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri!!)
                val frame = retriever.getFrameAtTime(500_000)!!
                try {
                    assertEquals(16f / 9f, frame.width.toFloat() / frame.height, .02f)
                    assertTrue(android.graphics.Color.green(frame.getPixel(2, 2)) > 150)
                } finally { frame.recycle() }
            } finally { retriever.release() }
        } finally {
            encoder.stop(); bitmap.recycle()
            uri?.let { context.contentResolver.delete(it, null, null) }
            context.cacheDir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
        }
    }
}
