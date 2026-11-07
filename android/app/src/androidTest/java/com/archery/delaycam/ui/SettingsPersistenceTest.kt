package com.archery.delaycam.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.archery.delaycam.settings.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsPersistenceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test(timeout = 60_000) fun rapidChangesPersistTheLastChoiceAndSurviveViewModelRecreation() {
        val repository = SettingsRepository(compose.activity)
        val original = runBlocking { repository.settingsFlow.first() }
        val owner = ViewModelStore()
        val restoredOwner = ViewModelStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var vm: DelayCamViewModel
        fun model(store: ViewModelStore) = ViewModelProvider(store,
            ViewModelProvider.AndroidViewModelFactory(compose.activity.applicationContext as Application))[DelayCamViewModel::class.java]
        try {
            compose.runOnUiThread { vm = model(owner) }
            val ready = scope.async { vm.awaitInitialSettings() }
            compose.waitUntil(10_000) { ready.isCompleted && vm.settings.value == original }
            val desired = original.copy(pictureMode = PictureMode.FILL, captureAspect = CaptureAspect.SIXTEEN_NINE,
                previewCorner = PreviewCorner.BOTTOM_LEFT,
                backtrackSec = if (original.backtrackSec == 30) 29 else 30)
            compose.runOnIdle {
                repeat(12) { i -> vm.updateSettings { it.copy(
                    pictureMode = if (i % 2 == 0) PictureMode.FIT else PictureMode.FILL,
                    captureAspect = if (i % 2 == 0) CaptureAspect.FOUR_THREE else CaptureAspect.SIXTEEN_NINE,
                    previewCorner = if (i % 2 == 0) PreviewCorner.BOTTOM_RIGHT else PreviewCorner.BOTTOM_LEFT) } }
                vm.updateSettings { desired }
                assertEquals(desired, vm.settings.value)
            }
            val persisted = scope.async { repository.settingsFlow.first { it == desired } }
            compose.waitUntil(15_000) { persisted.isCompleted }
            assertEquals(desired, runBlocking { persisted.await() })
            lateinit var recreated: DelayCamViewModel
            compose.runOnUiThread { owner.clear(); recreated = model(restoredOwner) }
            compose.waitUntil(10_000) { recreated.settings.value == desired }
            assertEquals(desired, runBlocking { repository.settingsFlow.first() })
        } finally {
            scope.cancel()
            compose.runOnUiThread { owner.clear(); restoredOwner.clear() }
            runBlocking { repository.save(original) }
        }
    }
}
