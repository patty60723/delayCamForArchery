package com.archery.delaycam.ui

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.archery.delaycam.camera.RecordingPhase
import com.archery.delaycam.state.SavePhase
import com.archery.delaycam.state.SaveStatus
import com.archery.delaycam.ui.components.RecordingControls
import com.archery.delaycam.ui.playback.PlaybackControls
import com.archery.delaycam.ui.theme.DelayCamTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RecordingPlaybackUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recordingCommandsAreClearAndBusyStateBlocksRepeat() {
        var phase by mutableStateOf(RecordingPhase.IDLE)
        var commands = 0
        compose.setContent {
            DelayCamTheme { RecordingControls(phase, 18, SaveStatus(), { commands++ }, {}, { _, _ -> }) }
        }
        compose.onNodeWithText("開始錄影").performClick()
        compose.runOnIdle { assertEquals(1, commands); phase = RecordingPhase.STARTING }
        compose.onNodeWithText("處理中…").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, commands); phase = RecordingPhase.RECORDING }
        compose.onNodeWithText("回放時相機持續錄影").assertIsDisplayed()
        compose.onNodeWithText("停止錄影").performClick()
        compose.runOnIdle { assertEquals(2, commands) }
    }

    @Test fun savingDoesNotClaimSuccessOrNavigate() {
        var save by mutableStateOf(SaveStatus(SavePhase.SAVING, message = "正在完成錄影並保存…"))
        var opened = 0
        compose.setContent {
            DelayCamTheme { RecordingControls(RecordingPhase.IDLE, 0, save, {}, {}, { _, _ -> opened++ }) }
        }
        compose.onNodeWithText("處理中…").assertIsNotEnabled()
        compose.onNodeWithText("查看").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, opened)
            save = SaveStatus(SavePhase.SAVED, Uri.parse("content://media/test/1"), "test.mp4", "上一段已保存，錄影已停止")
        }
        compose.onNodeWithText("上一段已保存，錄影已停止").assertIsDisplayed()
        compose.onNodeWithText("繼續錄影").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, opened) }
        compose.onNodeWithText("查看").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun failedSaveRemainsVisibleAndOffersRetry() {
        var retries = 0
        compose.setContent {
            DelayCamTheme { RecordingControls(RecordingPhase.IDLE, 0,
                SaveStatus(SavePhase.FAILED, message = "錄影儲存失敗，請確認空間後重試", retryable = true),
                {}, { retries++ }, { _, _ -> }) }
        }
        compose.onNodeWithText("錄影儲存失敗，請確認空間後重試").assertIsDisplayed()
        compose.onNodeWithText("開始錄影").assertDoesNotExist()
        compose.onNodeWithText("重試保存").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun playbackSpeedMirrorAndPauseAreDirectlyOperable() {
        lateinit var player: ExoPlayer
        var mirrored by mutableStateOf(true)
        var interactions = 0
        compose.runOnUiThread {
            player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext()).build()
            player.setMediaItem(MediaItem.fromUri("file:///test.mp4"))
        }
        try {
            compose.setContent { DelayCamTheme {
                PlaybackControls(player, mirrored, { mirrored = it }, {}, { interactions++ })
            } }
            compose.onNodeWithText("0.25×").performClick().assertIsSelected()
            compose.runOnIdle { assertEquals(0.25f, player.playbackParameters.speed, 0f) }
            compose.onNodeWithContentDescription("鏡像觀看").performClick()
            compose.runOnIdle { assertFalse(mirrored) }
            compose.onNodeWithText("播放", useUnmergedTree = true).performClick()
            compose.onNodeWithText("暫停", useUnmergedTree = true).assertIsDisplayed().performClick()
            compose.runOnIdle { assertFalse(player.playWhenReady); assertEquals(4, interactions) }
        } finally {
            compose.runOnUiThread { player.release() }
        }
    }
}
