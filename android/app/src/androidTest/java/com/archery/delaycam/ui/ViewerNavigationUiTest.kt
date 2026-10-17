package com.archery.delaycam.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.archery.delaycam.navigation.DelayCamNavHost
import com.archery.delaycam.ui.components.ViewerShareStatus
import com.archery.delaycam.ui.live.LivePracticeLayout
import com.archery.delaycam.ui.theme.DelayCamTheme
import com.archery.delaycam.viewer.LanViewerServer
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Controlled UI states; real camera/server continuity is in LanViewerIntegrationTest. */
class ViewerNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun orientation(landscape: Boolean) {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = if (landscape)
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation ==
            if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT }
    }

    @Test fun portraitPracticeEntryAndMissingAddressRecoveryShareOneWorkflow() {
        orientation(false)
        val owner = ViewModelStore()
        lateinit var vm: DelayCamViewModel
        compose.runOnUiThread { vm = ViewModelProvider(owner,
            ViewModelProvider.AndroidViewModelFactory(compose.activity.application))[DelayCamViewModel::class.java] }
        @Suppress("UNCHECKED_CAST")
        val state = DelayCamViewModel::class.java.getDeclaredField("_viewerShare")
            .apply { isAccessible = true }.get(vm) as MutableStateFlow<DelayCamViewModel.ViewerShareState>
        try {
            compose.setContent { DelayCamTheme { DelayCamNavHost(vm) } }
            compose.onNodeWithText("多裝置").performClick()
            compose.onNodeWithText("尚未開啟觀看連線").assertIsDisplayed()
            compose.onNodeWithText("開始錄影").assertDoesNotExist()
            compose.runOnIdle { state.value = DelayCamViewModel.ViewerShareState(error = "無法開啟觀看連線，請稍後重試。") }
            compose.onNodeWithText("無法開啟觀看連線，請稍後重試。").assertIsDisplayed()
            compose.onNodeWithText("開啟電腦觀看").assertIsEnabled()
            compose.runOnIdle { state.value = DelayCamViewModel.ViewerShareState(busy = true) }
            compose.onNodeWithText("正在開啟…").assertIsNotEnabled()
            compose.runOnIdle { state.value = DelayCamViewModel.ViewerShareState(running = true) }
            compose.onNodeWithText("重新取得連結").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("返回練習").performClick()
            compose.onNodeWithContentDescription("多裝置 · 已開啟").assertIsDisplayed()
            compose.onNodeWithText("設定").performClick()
            compose.onNodeWithText("關閉觀看連線").assertDoesNotExist()
            compose.onNodeWithText("多裝置觀看").assertDoesNotExist()
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithText("多裝置").performClick()
            compose.onNodeWithText("觀看連線已開啟").assertIsDisplayed()
            assertTrue(vm.viewerShare.value.running)
            compose.runOnIdle { vm.onAppBackgrounded() }
            compose.onNodeWithText("App 曾切到背景，觀看連線已關閉。請重新開啟，讓對方輸入畫面上的新網址；舊網址已失效。").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("開啟電腦觀看").performScrollTo().assertIsEnabled()
        } finally { compose.runOnUiThread { owner.clear() } }
    }

    @Test fun pendingOverlayKeepsFullLandscapeVideoAndNeverNavigatesAutomatically() {
        orientation(true)
        var state by mutableStateOf(DelayCamViewModel.ViewerShareState())
        var opens = 0
        try {
            compose.setContent { DelayCamTheme {
                Box(Modifier.width(560.dp).height(220.dp)) {
                    LivePracticeLayout(header = { Text("練習操作") },
                        video = { Box(it.testTag("viewer-practice-video")) }, controls = {},
                        connectionStatus = { ViewerShareStatus(state, { opens++ }, it) })
                }
            } }
            val bounds = compose.onNodeWithTag("viewer-practice-video").fetchSemanticsNode().boundsInRoot
            val layout = compose.onNodeWithTag("practice-layout").fetchSemanticsNode().boundsInRoot
            assertEquals(layout, bounds)
            compose.runOnIdle { state = DelayCamViewModel.ViewerShareState(busy = true) }
            compose.onNodeWithContentDescription("多裝置 · 開啟中").assertIsDisplayed()
            compose.runOnIdle { state = DelayCamViewModel.ViewerShareState(running = true) }
            compose.onNodeWithContentDescription("多裝置 · 已開啟").assertIsDisplayed()
            compose.runOnIdle { state = state.copy(requests = listOf(LanViewerServer.JoinRequest("a".repeat(48), "AAAAAA", "192.168.1.2"))) }
            compose.onNodeWithContentDescription("多裝置，1 個觀看要求待確認").assertIsDisplayed()
            assertEquals(0, opens)
            assertEquals(bounds, compose.onNodeWithTag("viewer-practice-video").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithContentDescription("多裝置，1 個觀看要求待確認").performClick()
            compose.runOnIdle { assertEquals(1, opens); state = DelayCamViewModel.ViewerShareState() }
            compose.onNodeWithContentDescription("多裝置，1 個觀看要求待確認").assertDoesNotExist()
            assertEquals(bounds, compose.onNodeWithTag("viewer-practice-video").fetchSemanticsNode().boundsInRoot)
        } finally { orientation(false) }
    }
}
