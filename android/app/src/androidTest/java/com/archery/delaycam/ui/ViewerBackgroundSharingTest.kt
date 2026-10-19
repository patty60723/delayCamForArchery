package com.archery.delaycam.ui

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.archery.delaycam.MainActivity
import com.archery.delaycam.camera.RecordingPhase
import com.archery.delaycam.service.ViewerForegroundService
import com.archery.delaycam.state.SavePhase
import com.archery.delaycam.viewer.LanViewerServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters
import org.junit.Test
import java.io.File
import java.net.Socket

/** Actual CameraX/FGS/HTTP frames while Android Settings owns the screen, not just a retained status flag. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ViewerBackgroundSharingTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private fun field(vm: DelayCamViewModel, name: String) = DelayCamViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm)
    private fun request(server: LanViewerServer, path: String, method: String = "GET"): ByteArray = Socket("127.0.0.1", server.port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write("$method $path HTTP/1.1\r\nHost: 127.0.0.1:${server.port}\r\n\r\n".toByteArray())
        val bytes = socket.getInputStream().readBytes()
        val boundary = bytes.indices.first { i -> i+3 < bytes.size && bytes[i] == 13.toByte() && bytes[i+1] == 10.toByte() && bytes[i+2] == 13.toByte() && bytes[i+3] == 10.toByte() }
        check(bytes.copyOfRange(0,boundary).toString(Charsets.US_ASCII).startsWith("HTTP/1.1 200"))
        bytes.copyOfRange(boundary+4,bytes.size)
    }
    private fun status(server: LanViewerServer) = JSONObject(request(server, "/v/${server.token}/status").toString(Charsets.UTF_8))
    private fun newest(server: LanViewerServer): Long = status(server).getJSONArray("segments").let { if (it.length() == 0) -1 else it.getJSONObject(it.length()-1).getLong("id") }
    private fun grant() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun notification() = context.getSystemService(NotificationManager::class.java).activeNotifications
        .firstOrNull { it.id == ViewerForegroundService.NOTIFICATION_ID }?.notification
    private fun decode(server: LanViewerServer, id: Long) {
        val file = File(context.cacheDir,"background-share-$id.mp4")
        file.writeBytes(request(server,"/v/${server.token}/segment/$id.mp4"))
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 0)
            val frame = retriever.getFrameAtTime(0)
            assertNotNull("A newly captured background segment must decode",frame)
            frame?.recycle()
        } finally { retriever.release(); file.delete() }
    }
    private fun background(scenario: ActivityScenario<MainActivity>, vm: DelayCamViewModel) {
        scenario.onActivity { it.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        compose.waitUntil(10_000) { field(vm,"uiForeground") == false }
    }

    @Test(timeout = 180_000) fun aAnotherAppKeepsNewFramesAndNotificationReturnsThenStopsAndSavesRecording() {
        grant()
        var saved: Uri? = null
        lateinit var vm: DelayCamViewModel
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { vm = it.sessionModel }
                compose.waitUntil(45_000) { vm.cameraSessionForFrame() != null && vm.warmupRemainingSec.value == null }
                val capture = vm.cameraSessionForFrame()
                val encoder = field(vm,"segmentEncoder")
                val generation = field(vm,"bufferGeneration")
                scenario.onActivity { vm.toggleContinuousRecording(); vm.startViewerShare() }
                compose.waitUntil(15_000) { vm.viewerShare.value.running && vm.recordingPhase.value == RecordingPhase.RECORDING }
                val server = field(vm,"viewerServer") as LanViewerServer
                val token = server.token
                val first = newest(server)
                background(scenario,vm)
                compose.waitUntil(35_000) { newest(server) >= first+2 }
                val last = newest(server)
                decode(server,last)
                assertEquals(capture,vm.cameraSessionForFrame())
                assertSame(encoder,field(vm,"segmentEncoder"))
                assertEquals(generation,field(vm,"bufferGeneration"))
                assertEquals(RecordingPhase.RECORDING,vm.recordingPhase.value)
                assertTrue(vm.sharingServiceActive)
                instrumentation.runOnMainSync { assertEquals(0,vm.livePlayer.mediaItemCount) }

                val join = "b".repeat(48)
                request(server,"/join/$join","POST")
                compose.waitUntil(10_000) { notification()?.extras?.getString(Notification.EXTRA_TITLE)?.contains("待確認") == true }
                assertTrue(device.openNotification())
                val target = device.wait(Until.findObject(By.textContains("觀看要求待確認")),10_000)
                assertNotNull("The foreground notification must be visible",target)
                target!!.click()
                compose.waitUntil(10_000) { field(vm,"uiForeground") == true }
                scenario.onActivity { assertSame(vm,it.sessionModel) }
                compose.onNodeWithText("核對碼 BBBBBB").performScrollTo().assertIsDisplayed()
                compose.onNodeWithText("允許觀看").performScrollTo().performClick()
                assertTrue(request(server,"/join/$join").toString(Charsets.UTF_8).contains(token))
                compose.onNodeWithText("返回練習").performClick()
                compose.waitUntil(15_000) { vm.warmupRemainingSec.value == null }
                assertEquals(generation,field(vm,"bufferGeneration"))
                assertSame(server,field(vm,"viewerServer"))
                assertEquals(capture,vm.cameraSessionForFrame())

                background(scenario,vm)
                val stop = notification()!!.actions.single { it.title.toString() == "停止分享" }.actionIntent
                stop.send()
                compose.waitUntil(15_000) { !vm.viewerShare.value.running && !vm.sharingServiceActive && vm.cameraSessionForFrame() == null }
                compose.waitUntil(45_000) { vm.recordingSave.value.phase == SavePhase.SAVED }
                saved = vm.recordingSave.value.uri
                assertNotNull(saved)
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context,saved!!)
                    val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
                    assertTrue("Recording must include time in another App",duration >= 5_000)
                    val frame = retriever.getFrameAtTime((duration-500).coerceAtLeast(0)*1_000)
                    assertNotNull(frame); frame?.recycle()
                } finally { retriever.release() }
                compose.waitUntil(10_000) { notification() == null }
                assertTrue(runCatching { status(server) }.isFailure)
            } finally { instrumentation.runOnMainSync { vm.stopViewerShare() } }
        }
        saved?.let { context.contentResolver.delete(it,null,null) }
    }

    @Test(timeout = 100_000) fun bActivityRecreationKeepsTheServiceCameraAndExistingLink() {
        grant()
        lateinit var vm: DelayCamViewModel
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                scenario.onActivity { vm = it.sessionModel }
                compose.waitUntil(45_000) { vm.cameraSessionForFrame() != null && vm.warmupRemainingSec.value == null }
                scenario.onActivity { vm.startViewerShare() }
                compose.waitUntil(15_000) { vm.viewerShare.value.running }
                val capture = vm.cameraSessionForFrame()
                val encoder = field(vm,"segmentEncoder")
                val server = field(vm,"viewerServer") as LanViewerServer
                val first = newest(server)
                scenario.recreate()
                scenario.onActivity { assertSame(vm,it.sessionModel) }
                assertSame(server,field(vm,"viewerServer"))
                assertSame(encoder,field(vm,"segmentEncoder"))
                assertEquals(capture,vm.cameraSessionForFrame())
                assertTrue(vm.sharingServiceActive)
                compose.waitUntil(25_000) { newest(server) > first }
                decode(server,newest(server))
            } finally { instrumentation.runOnMainSync { vm.stopViewerShare() } }
        }
    }

    @Test(timeout = 180_000) fun zBlockedNotificationChannelKeepsBackgroundFramesAndServiceLossRevokesTheLink() {
        grant()
        // Run last: user channel settings intentionally persist until the test package is removed.
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(ViewerForegroundService.CHANNEL) == null)
            manager.createNotificationChannel(android.app.NotificationChannel(ViewerForegroundService.CHANNEL, "拍攝與多裝置分享", NotificationManager.IMPORTANCE_LOW))
        lateinit var vm: DelayCamViewModel
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                try {
                    scenario.onActivity { vm = it.sessionModel }
                    compose.waitUntil(45_000) { vm.cameraSessionForFrame() != null && vm.warmupRemainingSec.value == null }
                    scenario.onActivity { it.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID,ViewerForegroundService.CHANNEL)) }
                    assertTrue(device.wait(Until.hasObject(By.pkg("com.android.settings")),10_000))
                    val switches = device.wait(Until.findObjects(By.pkg("com.android.settings").checkable(true)),10_000)
                    val allow = switches?.filter { it.isEnabled && it.isChecked }?.minByOrNull { it.visibleBounds.top }
                    assertNotNull("The system channel settings must expose an enabled notification switch",allow)
                    allow!!.click()
                    compose.waitUntil(10_000) { manager.getNotificationChannel(ViewerForegroundService.CHANNEL).importance == NotificationManager.IMPORTANCE_NONE }
                    device.pressBack()
                    compose.waitUntil(45_000) { field(vm,"uiForeground") == true && vm.cameraSessionForFrame() != null && vm.warmupRemainingSec.value == null }
                    scenario.onActivity { vm.startViewerShare() }
                    compose.waitUntil(15_000) { vm.viewerShare.value.running }
                    val server = field(vm,"viewerServer") as LanViewerServer
                    val first = newest(server)
                    background(scenario,vm)
                    compose.waitUntil(30_000) { newest(server) > first }
                    decode(server,newest(server))
                    assertTrue(vm.sharingServiceActive)
                    assertEquals(NotificationManager.IMPORTANCE_NONE,manager.getNotificationChannel(ViewerForegroundService.CHANNEL).importance)
                    assertFalse(ViewerForegroundService.notificationsVisible(context))
                    context.stopService(Intent(context,ViewerForegroundService::class.java))
                    compose.waitUntil(15_000) { !vm.sharingServiceActive && !vm.viewerShare.value.running && vm.cameraSessionForFrame() == null }
                    assertTrue(vm.viewerShare.value.notice?.contains("背景分享已停止") == true)
                    assertTrue(runCatching { status(server) }.isFailure)
                } finally { instrumentation.runOnMainSync { vm.stopViewerShare() } }
            }
        } finally { manager.deleteNotificationChannel(ViewerForegroundService.CHANNEL) }
    }
}
