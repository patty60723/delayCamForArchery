package com.archery.delaycam.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import android.view.Surface
import androidx.camera.core.ImageAnalysis
import androidx.camera.view.PreviewView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.archery.delaycam.MainActivity
import com.archery.delaycam.camera.RecordingPhase
import com.archery.delaycam.state.SavePhase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Exercise the actual manifest, camera, encoder and activity lifecycle together. */
class RotationContinuityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)

    @Test fun landscapeLaunchAndRotationKeepPreviewBufferAndRecording() {
        val automation = instrumentation.uiAutomation
        automation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
        var savedUri: android.net.Uri? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var activity: MainActivity
                lateinit var vm: DelayCamViewModel
                val renderedFrames = AtomicInteger()
                scenario.onActivity {
                    activity = it
                    // fullSensor intentionally ignores the OS user-rotation lock.
                    // Request the test orientation on the Activity itself.
                    it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    vm = ViewModelProvider(it)[DelayCamViewModel::class.java]
                    vm.livePlayer.setVideoFrameMetadataListener { _, _, _, _ -> renderedFrames.incrementAndGet() }
                }
                compose.waitUntil(10_000) {
                    activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                }
                compose.onNodeWithContentDescription("即時相機取景").assertIsDisplayed()
                compose.waitUntil(45_000) { vm.warmupRemainingSec.value == null && vm.cameraSessionForFrame() != null }
                val session = vm.cameraSessionForFrame()
                val generation = field(vm, "bufferGeneration")
                val encoder = field(vm, "segmentEncoder")
                assertNotNull(encoder)
                lateinit var preview: PreviewView
                scenario.onActivity {
                    preview = it.window.decorView.findViewWithTag("live-camera-preview-host")
                    vm.toggleContinuousRecording()
                }
                compose.waitUntil(10_000) { vm.recordingPhase.value == RecordingPhase.RECORDING }

                fun rotate(orientation: Int, expectedConfiguration: Int) {
                    scenario.onActivity { it.requestedOrientation = orientation }
                    compose.waitUntil(10_000) {
                        activity.resources.configuration.orientation == expectedConfiguration &&
                            activity.window.decorView.display.rotation == when (orientation) {
                                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> Surface.ROTATION_90
                                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> Surface.ROTATION_270
                                else -> Surface.ROTATION_0
                            } &&
                            (field(activity, "imageAnalysis") as? ImageAnalysis)?.targetRotation ==
                            activity.window.decorView.display.rotation
                    }
                    compose.waitForIdle()
                    scenario.onActivity {
                        assertSame("Rotation must not recreate MainActivity", activity, it)
                        assertSame(vm, ViewModelProvider(it)[DelayCamViewModel::class.java])
                        assertSame(preview, it.window.decorView.findViewWithTag<PreviewView>("live-camera-preview-host"))
                        assertEquals(session, vm.cameraSessionForFrame())
                        assertEquals("Rotation must not clear delay history", generation, field(vm, "bufferGeneration"))
                        assertSame("Keep the same encoder", encoder, field(vm, "segmentEncoder"))
                        assertNull("No new warmup after rotation", vm.warmupRemainingSec.value)
                        assertEquals(RecordingPhase.RECORDING, vm.recordingPhase.value)
                    }
                }

                screenshot("rotation-cold-landscape")
                rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
                compose.onNodeWithContentDescription("即時相機取景").assertIsDisplayed()
                compose.onNodeWithText("收起即時視窗").performClick()
                compose.onNodeWithContentDescription("即時相機取景").assertDoesNotExist()
                rotate(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
                compose.onNodeWithContentDescription("即時相機取景").assertDoesNotExist()
                compose.onNodeWithContentDescription("顯示操作").performClick()
                compose.onNodeWithText("顯示即時").performScrollTo().performClick()
                compose.onNodeWithContentDescription("收起操作").performClick()
                compose.onNodeWithContentDescription("即時相機取景").assertIsDisplayed()
                rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
                compose.onNodeWithContentDescription("即時相機取景").assertIsDisplayed()
                // Let the new orientation reach finalized segments and delayed playback.
                val elapsed = vm.recordingElapsedSec.value
                compose.waitUntil(20_000) { vm.recordingElapsedSec.value >= elapsed + 8 }
                val beforeFrames = renderedFrames.get()
                compose.waitUntil(10_000) { renderedFrames.get() >= beforeFrames + 5 }
                scenario.onActivity {
                    assertEquals(generation, field(vm, "bufferGeneration"))
                    assertNull(vm.warmupRemainingSec.value)
                    assertSame(encoder, field(vm, "segmentEncoder"))
                }
                screenshot("rotation-recording-landscape")
                scenario.onActivity { vm.toggleContinuousRecording() }
                compose.waitUntil(15_000) { vm.recordingSave.value.phase == SavePhase.SAVED }
                savedUri = vm.recordingSave.value.uri
                assertNotNull(savedUri)
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(instrumentation.targetContext, savedUri!!)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                    assertTrue("Recording must continue through rotation", duration >= 7_000)
                    val frame = retriever.getFrameAtTime((duration - 1_000) * 1_000)
                    assertNotNull("Post-rotation recording must decode", frame)
                    frame?.recycle()
                } finally { retriever.release() }

                // Genuine backgrounding must still discard old footage on return.
                scenario.moveToState(Lifecycle.State.CREATED)
                assertNull(vm.cameraSessionForFrame())
                scenario.moveToState(Lifecycle.State.RESUMED)
                compose.waitUntil(10_000) { vm.cameraSessionForFrame() != null }
                assertNotEquals(session, vm.cameraSessionForFrame())
                assertNotEquals(generation, field(vm, "bufferGeneration"))
            }
        } finally {
            savedUri?.let { instrumentation.targetContext.contentResolver.delete(it, null, null) }
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val resolver = instrumentation.targetContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ArcheryUiReview")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        val bitmap = instrumentation.uiAutomation.takeScreenshot()!!
        try { resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }
}
