package com.archery.delaycam.ui

import android.app.Application
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.archery.delaycam.camera.*
import com.archery.delaycam.gallery.ClipPresentationStore
import com.archery.delaycam.gallery.GalleryRepository
import com.archery.delaycam.settings.SettingsRepository
import com.archery.delaycam.state.AppState
import com.archery.delaycam.state.SavePhase
import com.archery.delaycam.ui.playback.CONTENT_ASPECT_KEY
import com.archery.delaycam.ui.replay.ReplayScreen
import com.archery.delaycam.ui.theme.DelayCamTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Real save -> replay -> keep/cancel -> delete -> MediaStore verification. */
class ReplayReviewDeleteTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun field(vm: DelayCamViewModel, name: String) = DelayCamViewModel::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(vm)
    @Suppress("UNCHECKED_CAST")
    @Test fun shotUsesFullLandscapeAndCanBeDeletedDirectlyFromReplay() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        val context = compose.activity
        val owner = ViewModelStore()
        lateinit var vm: DelayCamViewModel
        compose.runOnUiThread {
            vm = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))
                .get(DelayCamViewModel::class.java)
        }
        val loaded = runBlocking { SettingsRepository(context).settingsFlow.first() }
        compose.waitUntil(5_000) { vm.settings.value == loaded }
        val store = ImmutableSegmentStore(context.cacheDir, "replay_delete_${UUID.randomUUID()}")
        val file = store.allocate()
        instrumentation.context.assets.open("portrait-padded-landscape.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        store.publish(file)
        var uri: Uri? = null
        val frames = AtomicInteger()
        try {
            compose.runOnIdle {
                val capture = field(vm, "captureSession") as ForegroundCaptureSession
                capture.beginForeground()?.let { capture.markReady(it) }
                DelayCamViewModel::class.java.getDeclaredField("immutableStore").apply { isAccessible = true }.set(vm, store)
                val now = System.currentTimeMillis()
                (field(vm, "segmentBuffer") as SegmentBuffer).push(SegmentInfo(file, now - 2_000, now, 2_000, 4f / 3f))
                (field(vm, "_warmupRemainingSec") as MutableStateFlow<Int?>).value = null
                vm.replayPlayer.setVideoFrameMetadataListener { _, _, _, _ -> frames.incrementAndGet() }
                vm.triggerSave()
            }
            compose.waitUntil(10_000) { vm.appState.value == AppState.REPLAY }
            compose.runOnIdle { vm.markReplayInteraction() }
            compose.setContent { DelayCamTheme { ReplayScreen(vm, { _, _ -> }) } }
            compose.waitUntil(15_000) { vm.clipSave.value.phase == SavePhase.SAVED && frames.get() >= 3 }
            uri = vm.clipSave.value.uri!!
            compose.runOnIdle {
                assertEquals(4f / 3f, vm.replayPlayer.currentMediaItem!!.mediaMetadata.extras!!.getFloat(CONTENT_ASPECT_KEY), .001f)
            }
            val savedRanges = ClipPresentationStore(context).read(uri!!)
            assertEquals(4f / 3f, savedRanges.single().aspect!!, .001f)
            val layout = compose.onNodeWithTag("media-review-layout").fetchSemanticsNode().boundsInRoot
            val video = compose.onNodeWithTag("review-video").fetchSemanticsNode().boundsInRoot
            assertEquals(layout.width, video.width, 2f)
            assertEquals(layout.height, video.height, 2f)
            compose.onNodeWithText("返回練習").assertIsDisplayed()
            compose.onNodeWithText("刪除單箭").assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithContentDescription("顯示播放操作").performClick()
            compose.onNodeWithText("0.25×").performScrollTo().assertIsDisplayed()
            assertEquals(video, compose.onNodeWithTag("review-video").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithContentDescription("收起播放操作").performClick()
            screenshot("replay-fullscreen-delete-shot")

            compose.onNodeWithText("刪除單箭").performClick()
            compose.onNodeWithText("刪除這一箭？").assertIsDisplayed()
            compose.onNodeWithText("保留影片").performClick()
            assertTrue(runBlocking { GalleryRepository(context).clipExists(uri!!) })
            compose.runOnIdle { assertEquals(AppState.REPLAY, vm.appState.value) }
            compose.onNodeWithText("刪除單箭").performClick()
            compose.onNodeWithText("確認刪除").performClick()
            compose.waitUntil(10_000) { vm.appState.value == AppState.LIVE && !vm.clipDeletion.value.busy }
            assertFalse(runBlocking { GalleryRepository(context).clipExists(uri!!) })
            assertTrue(ClipPresentationStore(context).read(uri!!).isEmpty())
            assertNull(vm.clipSave.value.uri)
            assertFalse(vm.galleryClips.value.any { it.uri == uri })
        } finally {
            uri?.let { context.contentResolver.delete(it, null, null) }
            compose.runOnUiThread { owner.clear(); context.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            store.close()
        }
    }

    private fun screenshot(name: String) {
        val context = compose.activity
        val image = instrumentation.uiAutomation.takeScreenshot()!!
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ArcheryUiReview")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            context.contentResolver.openOutputStream(uri)!!.use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { image.recycle() }
    }
}
