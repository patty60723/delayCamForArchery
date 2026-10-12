package com.archery.delaycam.ui

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import com.archery.delaycam.camera.RecordingPhase
import com.archery.delaycam.state.SaveStatus
import com.archery.delaycam.state.SavePhase
import com.archery.delaycam.ui.components.RecordingControls
import com.archery.delaycam.ui.components.RecordingScreenLayout
import com.archery.delaycam.ui.components.SaveStatusNotice
import com.archery.delaycam.ui.live.DelayWarmupIndicator
import com.archery.delaycam.ui.live.LivePracticeLayout
import com.archery.delaycam.ui.live.LiveSaveActions
import com.archery.delaycam.ui.theme.DelayCamTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class LivePracticeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun orientation(landscape: Boolean) {
        compose.activityRule.scenario.onActivity {
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        compose.waitUntil(10_000) {
            compose.activity.resources.configuration.orientation ==
                if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        }
    }

    @After fun restorePortrait() { orientation(false) }

    private fun content(
        compact: Boolean = false,
        phase: RecordingPhase = RecordingPhase.IDLE,
        recordingSave: SaveStatus = SaveStatus(),
        clipSave: SaveStatus = SaveStatus(),
        fontScale: Float = 1f,
        onToggleRecording: () -> Unit = {},
        onRetryRecording: () -> Unit = {},
        onOpenSaved: (Uri, String) -> Unit = { _, _ -> },
        onSaveOnly: () -> Unit = {},
    ) {
        compose.setContent { DelayCamTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            Box((if (compact) Modifier.width(560.dp).height(220.dp) else Modifier.fillMaxSize())
                .background(MaterialTheme.colorScheme.background)) {
                RecordingScreenLayout(practice = true,
                    recording = { small, modifier ->
                        RecordingControls(phase, 18, recordingSave, onToggleRecording, onRetryRecording,
                            onOpenSaved, compact = small, modifier = modifier)
                    }, content = { screenModifier ->
                LivePracticeLayout(
                    header = { Text("延遲設定 10 秒 · 已就緒",
                        Modifier.padding(12.dp).testTag("practice-header")) },
                    video = { modifier -> Box(modifier.testTag("practice-video")
                        .background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                        // Layout fixture, not a claim of a running camera or rendered MP4.
                        Text("延遲影片區（版面測試）")
                    } },
                    controls = { sidePanel ->
                        LiveSaveActions(true, sidePanel, {}, onSaveOnly)
                        TextButton(onClick = {}) { Text("操作說明") }
                    },
                    landscapeFeedback = { modifier ->
                        if (clipSave.phase != SavePhase.IDLE) Surface(modifier) {
                            SaveStatusNotice(clipSave, {}, onOpenSaved)
                        }
                    },
                    modifier = screenModifier,
                )
                })
            }
            }
        } }
    }

    private fun checkWideVideo() {
        val layout = compose.onNodeWithTag("practice-layout").fetchSemanticsNode().boundsInRoot
        val video = compose.onNodeWithTag("practice-video").fetchSemanticsNode().boundsInRoot
        val screen = compose.onNodeWithTag("recording-screen-layout").fetchSemanticsNode().boundsInRoot
        assertTrue("Wide video must keep the available height", abs(layout.height - video.height) <= 2f)
        assertTrue("Video must keep all available width", abs(layout.width - video.width) <= 2f)
        assertTrue("Recording chrome must not reserve video height", abs(screen.height - video.height) <= 2f)
        compose.onNodeWithTag("practice-control-toggle").assertIsDisplayed().assertIsEnabled()
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        val resolver = compose.activity.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ArcheryUiReview")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        resolver.openOutputStream(uri)!!.use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    @Test fun landscapeVideoFillsScreenWithRecordingStopAlwaysAvailable() {
        orientation(true)
        var stopped = false
        content(phase = RecordingPhase.RECORDING, onToggleRecording = { stopped = true })
        checkWideVideo()
        compose.onNodeWithTag("practice-controls").assertDoesNotExist()
        compose.onNodeWithText("停止錄影").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(stopped) }
        screenshot("live-landscape-fullscreen")
    }

    @Test fun openingAndClosingControlsNeverResizesVideoOrSaves() {
        orientation(true)
        var saves = 0
        content(onSaveOnly = { saves++ })
        val before = compose.onNodeWithTag("practice-video").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithContentDescription("顯示操作").performClick()
        checkWideVideo()
        assertTrue(before == compose.onNodeWithTag("practice-video").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithText("存檔並回看").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("只保存").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(saves == 1) }
        screenshot("live-landscape-controls")
        compose.onNodeWithContentDescription("收起操作").performClick()
        compose.onNodeWithTag("practice-controls").assertDoesNotExist()
        checkWideVideo()
        compose.runOnIdle { assertTrue(saves == 1) }
    }

    @Test fun compactLandscapeControlsScrollAndBackClosesPanel() {
        orientation(true)
        content(compact = true)
        checkWideVideo()
        screenshot("live-compact-landscape-fullscreen")
        compose.onNodeWithContentDescription("顯示操作").performClick()
        compose.onNodeWithText("只保存").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("操作說明").performScrollTo().assertIsDisplayed()
        screenshot("live-compact-landscape-controls")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("practice-controls").assertDoesNotExist()
        checkWideVideo()
    }

    @Test fun landscapeSaveFailureRemainsVisibleWithControlsCollapsed() {
        orientation(true)
        var retries = 0
        content(recordingSave = SaveStatus(phase = SavePhase.FAILED, message = "整段影片保存失敗", retryable = true),
            clipSave = SaveStatus(phase = SavePhase.SAVED, message = "單箭影片已保存",
                uri = Uri.parse("content://media/external/video/media/123")),
            onRetryRecording = { retries++ })
        compose.onNodeWithTag("practice-controls").assertDoesNotExist()
        compose.onNodeWithText("整段影片保存失敗").assertIsDisplayed()
        compose.onNodeWithText("重試保存").assertIsDisplayed().performClick()
        compose.onNodeWithText("單箭影片已保存").assertIsDisplayed()
        compose.onNodeWithText("查看影片").assertIsDisplayed()
        compose.runOnIdle { assertTrue(retries == 1) }
        checkWideVideo()
        screenshot("live-landscape-save-feedback")
    }

    @Test fun compactRecordingBusyDisablesDuplicateRequests() {
        orientation(true)
        content(phase = RecordingPhase.STOPPING)
        compose.onNodeWithText("正在完成錄影…").assertIsDisplayed()
        compose.onNodeWithText("處理中…").assertIsNotEnabled()
        checkWideVideo()
    }

    @Test fun landscapeSavedRecordingOffersViewAndContinue() {
        orientation(true)
        var opened = false
        content(recordingSave = SaveStatus(phase = SavePhase.SAVED, message = "整段影片已保存",
            uri = Uri.parse("content://media/external/video/media/456")),
            onOpenSaved = { _, _ -> opened = true })
        compose.onNodeWithText("整段影片已保存").assertIsDisplayed()
        compose.onNodeWithText("繼續錄影").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("查看").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(opened) }
        screenshot("live-landscape-recording-saved")
    }

    @Test fun largeTextKeepsStopAndScrollableSaveActionsReachable() {
        orientation(true)
        content(compact = true, phase = RecordingPhase.RECORDING, fontScale = 1.5f)
        compose.onNodeWithText("停止錄影").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithContentDescription("顯示操作").performClick()
        compose.onNodeWithText("只保存").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("操作說明").performScrollTo().assertIsDisplayed()
        checkWideVideo()
        screenshot("live-landscape-large-text")
    }

    @Test fun portraitKeepsActionsBelowVideo() {
        orientation(false)
        content()
        val header = compose.onNodeWithTag("practice-header").fetchSemanticsNode().boundsInRoot
        val video = compose.onNodeWithTag("practice-video").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithTag("practice-controls").fetchSemanticsNode().boundsInRoot
        assertTrue(video.top >= header.bottom - 2f)
        assertTrue(controls.top >= video.bottom - 2f)
        compose.onNodeWithText("只保存").assertIsDisplayed()
        screenshot("live-portrait")
    }

    @Test fun warmupKeepsSecondsAndExplainsWaitingForFirstFrame() {
        orientation(false)
        var remaining by mutableStateOf<Int?>(10)
        compose.setContent { DelayCamTheme {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center) {
                remaining?.let { DelayWarmupIndicator(it, 10) }
            }
        } }
        compose.onNodeWithContentDescription("暖機剩餘 10 秒").assertIsDisplayed()
        compose.onNodeWithText("正在建立 10 秒延遲，請稍候").assertIsDisplayed()
        val pixels = compose.onNodeWithText("10").captureToImage().toPixelMap()
        var lightInk = false
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            if (pixels[x, y].luminance() > 0.5f) lightInk = true
        }
        assertTrue("Countdown must have readable light ink on the dark background", lightInk)
        screenshot("live-warmup-countdown")
        compose.runOnIdle { remaining = 3 }
        compose.onNodeWithContentDescription("暖機剩餘 3 秒").assertIsDisplayed()
        compose.runOnIdle { remaining = 0 }
        compose.onNodeWithContentDescription("暖機剩餘 0 秒").assertIsDisplayed()
        compose.onNodeWithText("正在等待第一個延遲畫面…").assertIsDisplayed()
        compose.onNodeWithTag("delay-warmup").assertIsDisplayed()
        screenshot("live-warmup-first-frame")
        compose.runOnIdle { remaining = null }
        compose.onNodeWithTag("delay-warmup").assertDoesNotExist()
    }
}
