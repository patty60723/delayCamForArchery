package com.archery.delaycam.camera

import androidx.test.platform.app.InstrumentationRegistry
import com.archery.delaycam.gallery.ClipPresentationStore
import com.archery.delaycam.gallery.GalleryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class ClipPresentationTest {
    @Test(timeout = 60_000) fun normalizedExportUsesActualCanvasAspectAndDeletionRemovesItsMetadata() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File.createTempFile("presentation", ".mp4", context.cacheDir)
        instrumentation.context.assets.open("clip-fixture.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        var uri: android.net.Uri? = null
        try {
            uri = ClipExporter.export(context, listOf(
                SegmentInfo(file, 0, 1000, 1000, 4f / 3f),
                SegmentInfo(file, 1000, 2000, 1000, null),
                SegmentInfo(file, 2000, 3000, 1000, .75f)), "presentation_${UUID.randomUUID()}.mp4")
            val store = ClipPresentationStore(context)
            val ranges = store.read(uri!!)
            assertEquals(3, ranges.size)
            assertEquals(4f / 3f, ClipPresentationStore.aspectAt(ranges, 500)!!, .001f)
            assertEquals(4f / 3f, ClipPresentationStore.aspectAt(ranges, 1500)!!, .001f)
            assertEquals(4f / 3f, ClipPresentationStore.aspectAt(ranges, 2500)!!, .001f)
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri!!)
                val frame = retriever.getFrameAtTime(500_000)!!
                try { assertEquals(4f / 3f, frame.width.toFloat() / frame.height, .02f) }
                finally { frame.recycle() }
            } finally { retriever.release() }
            GalleryRepository(context).deleteClip(uri!!)
            assertFalse(GalleryRepository(context).clipExists(uri!!))
            assertTrue(store.read(uri!!).isEmpty())
        } finally { uri?.let { runCatching { context.contentResolver.delete(it, null, null) } }; file.delete() }
    }
}
