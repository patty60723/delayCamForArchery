package com.archery.delaycam.ui

import android.Manifest
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.archery.delaycam.BuildConfig
import com.archery.delaycam.MainActivity
import com.archery.delaycam.viewer.LanViewerServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.Socket

/** Actual CameraX -> immutable encoder files -> authenticated HTTP -> decodable MP4. */
class LanViewerIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private fun field(vm: DelayCamViewModel, name: String) = DelayCamViewModel::class.java
        .getDeclaredField(name).apply { isAccessible = true }.get(vm)
    private fun request(port: Int, path: String): Pair<String, ByteArray> = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5000
        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray())
        val bytes = socket.getInputStream().readBytes()
        val boundary = bytes.indices.first { i -> i + 3 < bytes.size && bytes[i] == 13.toByte() && bytes[i+1] == 10.toByte() && bytes[i+2] == 13.toByte() && bytes[i+3] == 10.toByte() }
        bytes.copyOfRange(0, boundary).toString(Charsets.UTF_8) to bytes.copyOfRange(boundary+4, bytes.size)
    }

    @Test(timeout = 150_000) fun cameraShareSurvivesRotationAndRevokesOnStopOrBackground() {
        assertTrue(BuildConfig.LAN_VIEWER_TRIAL)
        instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, Manifest.permission.CAMERA)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: DelayCamViewModel
            lateinit var activity: MainActivity
            scenario.onActivity { activity = it; it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                vm = ViewModelProvider(it)[DelayCamViewModel::class.java] }
            compose.waitUntil(45_000) { vm.warmupRemainingSec.value == null && vm.cameraSessionForFrame() != null }
            val cameraSession = vm.cameraSessionForFrame()
            val encoder = field(vm, "segmentEncoder")
            compose.onNodeWithContentDescription("顯示操作").performClick()
            compose.onNodeWithText("設定").performClick()
            compose.onNodeWithText("開啟電腦觀看").performScrollTo().performClick()
            compose.waitUntil(15_000) { vm.viewerShare.value.running }
            compose.onNodeWithText("關閉觀看連線").performScrollTo().assertIsDisplayed()
            val resolver = activity.contentResolver
            val image = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "lan-viewer-settings.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ArcheryUiReview")
            })!!
            val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
            resolver.openOutputStream(image)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val server = field(vm, "viewerServer") as LanViewerServer
            val token = server.token
            val path = "/v/$token/"
            val response = request(server.port, "${path}status")
            assertTrue(response.first.startsWith("HTTP/1.1 200"))
            val status = JSONObject(response.second.toString(Charsets.UTF_8))
            assertTrue(status.getBoolean("active"))
            assertEquals(vm.settings.value.delaySec, status.getInt("delaySec"))
            val segments = status.getJSONArray("segments")
            assertTrue(segments.length() > 0)
            val samples = File(activity.getExternalFilesDir(null), "viewer-ci").apply { mkdirs() }
            for (i in maxOf(0, segments.length()-3) until segments.length()) {
                val id = segments.getJSONObject(i).getLong("id")
                val media = request(server.port, "${path}segment/$id.mp4")
                assertTrue(media.first.startsWith("HTTP/1.1 200"))
                val file = File(samples, "sample-$id.mp4").apply { writeBytes(media.second) }
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)!!.toInt() > 0)
                    assertTrue(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() > 0)
                } finally { retriever.release() }
            }
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            compose.waitUntil(15_000) { activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
            assertTrue(vm.viewerShare.value.running)
            assertSame(server, field(vm, "viewerServer"))
            assertSame(encoder, field(vm, "segmentEncoder"))
            assertEquals(cameraSession, vm.cameraSessionForFrame())
            assertTrue(request(server.port, "${path}status").first.startsWith("HTTP/1.1 200"))
            val oldPort = server.port
            compose.onNodeWithText("關閉觀看連線").performScrollTo().performClick()
            assertFalse(vm.viewerShare.value.running)
            assertTrue(runCatching { request(oldPort, "${path}status") }.isFailure)
            compose.onNodeWithText("開啟電腦觀看").performScrollTo().performClick()
            compose.waitUntil(15_000) { vm.viewerShare.value.running }
            val next = field(vm, "viewerServer") as LanViewerServer
            assertNotEquals(token, next.token)
            assertTrue(request(next.port, "${path}status").first.startsWith("HTTP/1.1 404"))
            val nextPort = next.port
            scenario.moveToState(Lifecycle.State.CREATED)
            compose.waitUntil(10_000) { !vm.viewerShare.value.running }
            assertTrue(runCatching { request(nextPort, "/v/${next.token}/status") }.isFailure)
            scenario.onActivity { vm.startViewerShare() }
            assertFalse(vm.viewerShare.value.running)
        }
    }
}
