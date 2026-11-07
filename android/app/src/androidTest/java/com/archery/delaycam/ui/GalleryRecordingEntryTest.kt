package com.archery.delaycam.ui

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.archery.delaycam.camera.RecordingPhase
import com.archery.delaycam.navigation.DelayCamNavHost
import com.archery.delaycam.state.SavePhase
import com.archery.delaycam.state.SaveStatus
import com.archery.delaycam.ui.components.RecordingControls
import com.archery.delaycam.ui.components.RecordingScreenLayout
import com.archery.delaycam.ui.theme.DelayCamTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GalleryRecordingEntryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun screenshot(name: String) {
        val resolver = compose.activity.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ArcheryUiReview")
        })!!
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        resolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test(timeout = 90_000) fun navigationRestrictsNewRecordingToPractice() {
        val owner = ViewModelStore()
        lateinit var vm: DelayCamViewModel
        compose.runOnUiThread {
            vm = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(compose.activity.application))
                .get(DelayCamViewModel::class.java)
        }
        try {
            compose.setContent { DelayCamTheme { DelayCamNavHost(vm) } }
            compose.onNodeWithText("開始錄影").assertIsDisplayed()
            compose.onNodeWithText("影片").performClick()
            compose.onNodeWithText("畫廊").assertIsDisplayed()
            compose.onNodeWithText("開始錄影").assertDoesNotExist()
            compose.onNodeWithText("整段錄影").assertDoesNotExist()
            screenshot("gallery-no-recording-entry")
            @Suppress("UNCHECKED_CAST")
            val saved = DelayCamViewModel::class.java.getDeclaredField("_recordingSave")
                .apply { isAccessible = true }.get(vm) as MutableStateFlow<SaveStatus>
            compose.runOnUiThread {
                saved.value = SaveStatus(SavePhase.SAVED, Uri.parse("content://media/external/video/media/1"),
                    "recording.mp4", "錄影已保存")
            }
            compose.onNodeWithText("錄影已保存").assertIsDisplayed()
            compose.onNodeWithText("繼續錄影").assertDoesNotExist()
            compose.onNodeWithText("返回", useUnmergedTree = true).performClick()
            compose.onNodeWithText("繼續錄影").assertIsDisplayed()
            compose.onNodeWithText("設定").performClick()
            compose.onNodeWithText("開始錄影").assertDoesNotExist()
            compose.onNodeWithText("繼續錄影").assertDoesNotExist()
            compose.onNodeWithText("錄影已保存").assertIsDisplayed()
        } finally { compose.runOnUiThread { owner.clear() } }
    }

    @Test fun browsingRetainsStopAndSaveRecoveryWithoutStartCommands() {
        var phase by mutableStateOf(RecordingPhase.IDLE)
        var save by mutableStateOf(SaveStatus())
        var commands = 0
        var retries = 0
        var opens = 0
        compose.setContent { DelayCamTheme {
            RecordingScreenLayout(practice = false,
                recording = { compact, modifier -> RecordingControls(phase, 18, save,
                    { commands++ }, { retries++ }, { _, _ -> opens++ },
                    compact = compact, modifier = modifier, allowStart = false) },
                content = { Text("畫廊", it) })
        } }
        compose.onNodeWithText("畫廊").assertIsDisplayed()
        compose.onNodeWithText("整段錄影").assertDoesNotExist()
        compose.onNodeWithText("開始錄影").assertDoesNotExist()
        compose.runOnIdle { phase = RecordingPhase.RECORDING }
        compose.onNodeWithText("停止錄影").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, commands); phase = RecordingPhase.STOPPING }
        compose.onNodeWithText("處理中…").assertIsNotEnabled()
        compose.runOnIdle { phase = RecordingPhase.IDLE; save = SaveStatus(SavePhase.SAVING, message = "正在保存錄影…") }
        compose.onNodeWithText("正在保存錄影…").assertIsDisplayed()
        compose.onNodeWithText("開始錄影").assertDoesNotExist()
        compose.runOnIdle { save = SaveStatus(SavePhase.FAILED, message = "保存失敗", retryable = true) }
        compose.onNodeWithText("重試保存").performClick()
        compose.runOnIdle { assertEquals(1, retries)
            save = SaveStatus(SavePhase.SAVED, Uri.parse("content://media/external/video/media/1"), "test.mp4", "錄影已保存") }
        compose.onNodeWithText("查看影片").performClick()
        compose.onNodeWithText("繼續錄影").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, opens); assertEquals(1, commands) }
    }
}
