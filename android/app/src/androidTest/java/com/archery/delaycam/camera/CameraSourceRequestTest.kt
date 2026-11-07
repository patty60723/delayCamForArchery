package com.archery.delaycam.camera

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.archery.delaycam.settings.CaptureAspect
import com.archery.delaycam.ui.components.CaptureAspectControl
import com.archery.delaycam.ui.live.PracticeCameraLayout
import com.archery.delaycam.ui.theme.DelayCamTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CameraSourceRequestTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test(timeout = 90_000) fun selectedRatioNegotiatesRealCameraAndFallbackKeepsThePreviewHost() {
        val activity = compose.activity
        InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(activity.packageName, Manifest.permission.CAMERA)
        val provider = ProcessCameraProvider.getInstance(activity).get(10, TimeUnit.SECONDS)
        val executor = Executors.newSingleThreadExecutor()
        val frames = AtomicInteger()
        var preview by mutableStateOf(cameraPreview())
        var status by mutableStateOf(CameraSourceStatus())
        var analysis: ImageAnalysis? = null
        var requested by mutableStateOf(CaptureAspect.FOUR_THREE)
        compose.setContent { DelayCamTheme {
            PracticeCameraLayout(preview, true) { report ->
                Column {
                    Box(Modifier.size(160.dp, 120.dp).onGloballyPositioned { report(it.boundsInRoot()) })
                    CaptureAspectControl(requested, true, status) { }
                }
            }
        } }
        fun bind(target: CaptureAspect, simulateUnsupportedWide: Boolean = false) {
            val before = frames.get()
            compose.runOnIdle {
                requested = target
                analysis?.clearAnalyzer()
                provider.unbindAll()
                val (actual, pair) = bindPreferredSource(target) { candidate ->
                    if (simulateUnsupportedWide && candidate == CaptureAspect.SIXTEEN_NINE)
                        throw IllegalArgumentException("simulated unsupported 16:9")
                    val nextPreview = cameraPreview(candidate)
                    val nextAnalysis = delayImageAnalysis(candidate)
                    nextAnalysis.setAnalyzer(executor) { image ->
                        try {
                            val bitmap = image.toRotatedBitmap()
                            bitmap.recycle()
                            frames.incrementAndGet()
                        } finally { image.close() }
                    }
                    try {
                        provider.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, nextPreview, nextAnalysis)
                        val size = nextAnalysis.resolutionInfo!!.resolution
                        assertTrue("Actual source $size must match $candidate", matchesSourceAspect(size.width, size.height, candidate))
                        nextPreview to nextAnalysis
                    } catch (failure: Exception) {
                        nextAnalysis.clearAnalyzer(); provider.unbind(nextPreview, nextAnalysis); throw failure
                    }
                }
                preview = pair.first
                analysis = pair.second
                status = CameraSourceStatus(target, actual, switching = false)
            }
            compose.waitUntil(15_000) { frames.get() >= before + 3 }
        }
        try {
            bind(CaptureAspect.FOUR_THREE)
            lateinit var host: PreviewView
            compose.runOnIdle { host = activity.window.decorView.findViewWithTag("live-camera-preview-host") }
            bind(CaptureAspect.SIXTEEN_NINE)
            assertEquals("Emulated front camera supports real 16:9", CaptureAspect.SIXTEEN_NINE, status.source)
            compose.onNodeWithText("相機來源：16:9").assertIsDisplayed()
            compose.runOnIdle { assertSame(host, activity.window.decorView.findViewWithTag<PreviewView>("live-camera-preview-host")) }
            bind(CaptureAspect.SIXTEEN_NINE, simulateUnsupportedWide = true)
            assertEquals(CaptureAspect.FOUR_THREE, status.source)
            compose.onNodeWithText("相機來源：4:3（16:9 無法使用，已改用 4:3 來源）").assertIsDisplayed()
            compose.runOnIdle { assertSame(host, activity.window.decorView.findViewWithTag<PreviewView>("live-camera-preview-host")) }
            bind(CaptureAspect.FOUR_THREE)
            compose.onNodeWithText("相機來源：4:3").assertIsDisplayed()
        } finally {
            compose.runOnIdle { analysis?.clearAnalyzer(); provider.unbindAll() }
            executor.shutdown()
        }
    }
    @Test fun sourceStatusCommunicatesSwitchingFailureAndRecordingLock() {
        var status by mutableStateOf(CameraSourceStatus(CaptureAspect.SIXTEEN_NINE))
        var enabled by mutableStateOf(true)
        compose.setContent { DelayCamTheme {
            CaptureAspectControl(CaptureAspect.SIXTEEN_NINE, enabled, status) { }
        } }
        compose.onNodeWithText("正在切換相機比例…").assertIsDisplayed()
        compose.runOnIdle { status = CameraSourceStatus(CaptureAspect.SIXTEEN_NINE, switching = false, failed = true) }
        compose.onNodeWithText("相機無法啟動，請關閉後重新開啟 App。").assertIsDisplayed()
        compose.runOnIdle {
            status = CameraSourceStatus(CaptureAspect.SIXTEEN_NINE, CaptureAspect.FOUR_THREE, switching = false)
            enabled = false
        }
        compose.onNodeWithText("16:9").assertIsSelected().assertIsNotEnabled()
        compose.onNodeWithText("4:3").assertIsNotEnabled()
        compose.onNodeWithText("錄影中，停止錄影後可切換取景比例。").assertIsDisplayed()
        compose.onNodeWithText("相機來源：4:3（16:9 無法使用，已改用 4:3 來源）").assertIsDisplayed()
    }

}
