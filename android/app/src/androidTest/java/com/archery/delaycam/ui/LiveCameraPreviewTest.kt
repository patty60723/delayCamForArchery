package com.archery.delaycam.ui

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.archery.delaycam.ui.live.LiveCameraPreview
import com.archery.delaycam.ui.theme.DelayCamTheme
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LiveCameraPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun hiddenPreviewStartsCameraAndTogglingKeepsItsHostAndFrames() {
        val activity = compose.activity
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(activity.packageName, Manifest.permission.CAMERA)
        val provider = ProcessCameraProvider.getInstance(activity).get(10, TimeUnit.SECONDS)
        val preview = Preview.Builder().build()
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        val executor = Executors.newSingleThreadExecutor()
        val frames = AtomicInteger()
        analysis.setAnalyzer(executor) { frame ->
            frames.incrementAndGet()
            frame.close()
        }
        var visible by mutableStateOf(false)
        try {
            compose.setContent { DelayCamTheme {
                Box(Modifier.fillMaxSize()) {
                    LiveCameraPreview(preview, visible, 96.dp, Modifier.align(Alignment.BottomEnd))
                }
            } }
            compose.runOnIdle {
                provider.bindToLifecycle(activity, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            }
            compose.waitUntil(20_000) { frames.get() >= 3 }
            compose.onNodeWithContentDescription("即時相機取景").assertDoesNotExist()
            lateinit var host: PreviewView
            compose.runOnIdle { host = activity.window.decorView.findViewWithTag("live-camera-preview-host") }
            val beforeShowing = frames.get()
            compose.runOnIdle { visible = true }
            compose.onNodeWithContentDescription("即時相機取景").assertIsDisplayed()
            compose.waitUntil(10_000) { frames.get() >= beforeShowing + 3 }
            compose.runOnIdle {
                assertSame(host, activity.window.decorView.findViewWithTag<PreviewView>("live-camera-preview-host"))
                visible = false
            }
            val beforeHiding = frames.get()
            compose.waitUntil(10_000) { frames.get() >= beforeHiding + 3 }
            compose.onNodeWithContentDescription("即時相機取景").assertDoesNotExist()
            compose.runOnIdle {
                assertSame(host, activity.window.decorView.findViewWithTag<PreviewView>("live-camera-preview-host"))
            }
        } finally {
            compose.runOnIdle { provider.unbindAll() }
            analysis.clearAnalyzer()
            executor.shutdown()
        }
    }
}
