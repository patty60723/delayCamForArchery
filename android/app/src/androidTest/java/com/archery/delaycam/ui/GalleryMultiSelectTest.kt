package com.archery.delaycam.ui

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.archery.delaycam.gallery.GalleryRepository
import com.archery.delaycam.ui.gallery.GalleryScreen
import com.archery.delaycam.ui.gallery.clipShareIntent
import com.archery.delaycam.ui.theme.DelayCamTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class GalleryMultiSelectTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun screenshot(name: String) {
        val resolver = compose.activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ArcheryUiReview")
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun shareIntentGrantsReadAccessToEverySelectedStream() {
        val a = Uri.parse("content://media/external/video/media/101")
        val b = Uri.parse("content://media/external/video/media/102")
        val single = clipShareIntent(listOf(a, a))
        assertEquals(Intent.ACTION_SEND, single.action)
        assertEquals(a, single.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        val multiple = clipShareIntent(listOf(a, b, a))
        assertEquals(Intent.ACTION_SEND_MULTIPLE, multiple.action)
        assertEquals(arrayListOf(a, b), multiple.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))
        assertEquals(2, multiple.clipData!!.itemCount)
        assertEquals(b, multiple.clipData!!.getItemAt(1).uri)
        assertEquals("video/mp4", multiple.type)
        assertTrue(multiple.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(0, multiple.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
    }

    @Test(timeout = 90_000) fun longPressMultiSelectCancelAndDeleteOnlySelectedClips() {
        val context = compose.activity
        val repo = GalleryRepository(context)
        val owner = ViewModelStore()
        lateinit var vm: DelayCamViewModel
        val prefix = "gallery_test_${UUID.randomUUID()}"
        val created = mutableListOf<Uri>()
        fun create(name: String, path: String = "Movies/Archery/"): Uri {
            val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, path)
                put(MediaStore.Video.Media.IS_PENDING, 1)
            })!!
            created += uri
            InstrumentationRegistry.getInstrumentation().context.assets.open("clip-fixture.mp4").use { input ->
                context.contentResolver.openOutputStream(uri)!!.use { input.copyTo(it) }
            }
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return uri
        }
        val a = create("${prefix}_a.mp4")
        val b = create("${prefix}_b.mp4")
        val keep = create("${prefix}_keep.mp4")
        val outside = create("${prefix}_outside.mp4", "Movies/ArcheryBackup/")
        var opened = 0
        try {
            compose.runOnUiThread {
                vm = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))
                    .get(DelayCamViewModel::class.java)
            }
            compose.setContent { DelayCamTheme { GalleryScreen(vm, {}, { opened++ }) } }
            compose.waitUntil(15_000) { vm.galleryClips.value.any { it.uri == a } }
            assertFalse(vm.galleryClips.value.any { it.uri == outside })
            compose.onNodeWithText("${prefix}_a.mp4").performScrollTo().performTouchInput { longClick() }
            compose.onNodeWithText("已選 1 段").assertExists()
            assertEquals(0, opened)
            compose.onNodeWithText("${prefix}_b.mp4").performScrollTo().performClick()
            compose.onNodeWithText("已選 2 段").assertExists()
            screenshot("gallery-multiselect")
            compose.onNodeWithText("刪除", useUnmergedTree = true).performClick()
            compose.onNodeWithText("刪除 2 段影片？").assertExists()
            compose.onNodeWithText("保留影片").performClick()
            runBlocking { assertTrue(repo.clipExists(a)); assertTrue(repo.clipExists(b)) }
            compose.onNodeWithText("刪除", useUnmergedTree = true).performClick()
            compose.onNodeWithText("確認刪除").performClick()
            compose.waitUntil(15_000) { !vm.batchDeleting.value && !vm.gallerySelection.value.active }
            runBlocking {
                assertFalse(repo.clipExists(a)); assertFalse(repo.clipExists(b))
                assertTrue(repo.clipExists(keep)); assertTrue(repo.clipExists(outside))
            }
            compose.onNodeWithText("已刪除 2 段影片").assertExists()
            compose.onNodeWithText("${prefix}_keep.mp4").performScrollTo().performClick()
            assertEquals(1, opened)
            compose.onNodeWithText("選取", useUnmergedTree = true).performClick()
            compose.onNodeWithText("已選 0 段").assertExists()
            compose.onNodeWithText("分享", useUnmergedTree = true).assertIsNotEnabled()
            compose.onNodeWithText("刪除", useUnmergedTree = true).assertIsNotEnabled()
            compose.onNodeWithText("取消", useUnmergedTree = true).performClick()
            compose.onNodeWithText("畫廊").assertExists()
        } finally {
            compose.runOnUiThread { owner.clear() }
            created.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }
}
