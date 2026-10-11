package com.archery.delaycam.ui

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.archery.delaycam.camera.RecordingPhase
import com.archery.delaycam.state.SaveStatus
import com.archery.delaycam.ui.components.RecordingControls
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

    private fun content(compact: Boolean = false) {
        compose.setContent { DelayCamTheme {
            Column(Modifier.fillMaxSize().safeDrawingPadding()
                .background(MaterialTheme.colorScheme.background)) {
                RecordingControls(RecordingPhase.IDLE, 0, SaveStatus(), {}, {}, { _, _ -> })
                LivePracticeLayout(
                    header = { Text("延遲設定 10 秒 · 已就緒",
                        Modifier.padding(12.dp).testTag("practice-header")) },
                    video = { modifier -> Box(modifier.testTag("practice-video")
                        .background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                        // Layout fixture, not a claim of a running camera or rendered MP4.
                        Text("延遲影片區（版面測試）")
                    } },
                    controls = { sidePanel ->
                        LiveSaveActions(true, sidePanel, {}, {})
                        TextButton(onClick = {}) { Text("操作說明") }
                    },
                    modifier = if (compact) Modifier.width(560.dp).height(220.dp) else Modifier.weight(1f),
                )
            }
        } }
    }

    private fun checkWideVideo() {
        val layout = compose.onNodeWithTag("practice-layout").fetchSemanticsNode().boundsInRoot
        val video = compose.onNodeWithTag("practice-video").fetchSemanticsNode().boundsInRoot
        val controls = compose.onNodeWithTag("practice-controls").fetchSemanticsNode().boundsInRoot
        assertTrue("Wide video must keep the available height", abs(layout.height - video.height) <= 2f)
        assertTrue("Video must keep most of the width", video.width >= layout.width * 0.60f)
        assertTrue("Controls belong beside the video", controls.left >= video.right - 2f)
        compose.onNodeWithText("存檔並回看").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("只保存").assertIsDisplayed().assertIsEnabled()
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

    @Test fun landscapeUsesVideoHeightInsteadOfStackingActions() {
        orientation(true)
        content()
        checkWideVideo()
        screenshot("live-landscape")
    }

    @Test fun compactLandscapeAlsoKeepsActionsBesideVideo() {
        orientation(true)
        content(compact = true)
        checkWideVideo()
        screenshot("live-compact-landscape")
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
