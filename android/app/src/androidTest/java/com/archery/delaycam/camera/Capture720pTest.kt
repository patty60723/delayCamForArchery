package com.archery.delaycam.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.archery.delaycam.gesture.gestureFrameCopy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class Capture720pTest {
    @Test fun gestureCopyHasBoundedSizeAndDoesNotRetainRecycledCapture() {
        for ((w, h) in listOf(1280 to 960, 960 to 1280, 320 to 240)) {
            val original = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            original.eraseColor(Color.GREEN)
            val gesture = gestureFrameCopy(original)
            assertNotSame(original, gesture)
            original.recycle()
            try {
                assertTrue(maxOf(gesture.width, gesture.height) <= 640)
                assertEquals(w.toFloat() / h, gesture.width.toFloat() / gesture.height, .005f)
                assertEquals(Color.GREEN, gesture.getPixel(0, 0))
            } finally { gesture.recycle() }
        }
    }

    @Test(timeout = 180_000) fun portraitLaunchThenLandscapeRetain720pDetailWithOneEncoderAndExportCorrectly() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val segments = Collections.synchronizedList(mutableListOf<SegmentInfo>())
        val stopped = CountDownLatch(1)
        val prefix = "quality720_${System.nanoTime()}"
        val encoder = ContinuousSegmentEncoder(context.cacheDir, { segments.add(it) },
            { stopped.countDown() }, filePrefix = prefix)
        val wide = Bitmap.createBitmap(1280, 960, Bitmap.Config.ARGB_8888)
        val tall = Bitmap.createBitmap(960, 1280, Bitmap.Config.ARGB_8888)
        fun stripes(bitmap: Bitmap, vertical: Boolean) {
            bitmap.eraseColor(Color.BLACK)
            val canvas = Canvas(bitmap)
            val paint = Paint().apply { color = Color.WHITE }
            for (i in 0 until 1280 step 8) {
                if (vertical) canvas.drawRect(i.toFloat(), 0f, i + 4f, bitmap.height.toFloat(), paint)
                else canvas.drawRect(0f, i.toFloat(), bitmap.width.toFloat(), i + 4f, paint)
            }
        }
        stripes(wide, true); stripes(tall, false)
        try {
            var firstCodec: Any? = null
            val codecField = encoder.javaClass.getDeclaredField("codec").apply { isAccessible = true }
            for ((bitmap, aspect) in listOf(tall to 9f / 16f, wide to 16f / 9f)) {
                val until = SystemClock.elapsedRealtime() + 4500
                while (SystemClock.elapsedRealtime() < until) {
                    encoder.onFrame(bitmap, aspect)
                    if (firstCodec == null) firstCodec = codecField.get(encoder)
                    assertSame("Rotation must retain the codec", firstCodec, codecField.get(encoder))
                    SystemClock.sleep(50)
                }
            }
            encoder.stop()
            assertTrue(stopped.await(10, TimeUnit.SECONDS))
            for (aspect in listOf(9f / 16f, 16f / 9f)) {
                val segment = segments.firstOrNull { it.contentAspectRatio == aspect }
                assertNotNull("Need a pure segment at aspect $aspect", segment)
                val raw = MediaMetadataRetriever()
                try {
                    raw.setDataSource(segment!!.file.absolutePath)
                    assertEquals("1280", raw.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                    assertEquals("1280", raw.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                } finally { raw.release() }
                val uri = ClipExporter.export(context, listOf(segment!!), "$prefix-$aspect.mp4".replace('/', '_'))
                try {
                    val video = MediaMetadataRetriever()
                    try {
                        video.setDataSource(context, uri)
                        val frame = video.getFrameAtTime(100_000, MediaMetadataRetriever.OPTION_CLOSEST)!!
                        try {
                            val portrait = aspect < 1f
                            assertEquals(if (portrait) 720 else 1280, frame.width)
                            assertEquals(if (portrait) 1280 else 720, frame.height)
                            // Four-pixel bars would blur together if the orientation had
                            // first been shrunk into the old portrait/landscape canvas.
                            val white = if (portrait) frame.getPixel(frame.width / 2, 34) else frame.getPixel(34, frame.height / 2)
                            val black = if (portrait) frame.getPixel(frame.width / 2, 38) else frame.getPixel(38, frame.height / 2)
                            assertTrue("Fine white stripe must survive at $aspect", Color.red(white) > 180)
                            assertTrue("Fine black stripe must survive at $aspect", Color.red(black) < 80)
                        } finally { frame.recycle() }
                    } finally { video.release() }
                } finally {
                    com.archery.delaycam.gallery.ClipPresentationStore(context).remove(uri)
                    context.contentResolver.delete(uri, null, null)
                }
            }
        } finally {
            encoder.stop(); wide.recycle(); tall.recycle()
            context.cacheDir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
        }
    }
}
