package com.archery.delaycam.ui

import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.archery.delaycam.ui.playback.CONTENT_ASPECT_KEY
import com.archery.delaycam.ui.playback.VideoPlayer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class ActivePictureUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun encodedPaddingIsRemovedWithoutCroppingTopOrBottomOfPicture() {
        compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        val context = compose.activity
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(context.cacheDir, "active-picture.mp4")
        instrumentation.context.assets.open("portrait-padded-landscape.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        lateinit var player: ExoPlayer
        val frames = AtomicInteger()
        compose.activityRule.scenario.onActivity {
            player = ExoPlayer.Builder(it).build()
            player.setVideoFrameMetadataListener { _, _, _, _ -> frames.incrementAndGet() }
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.setMediaItem(MediaItem.Builder().setUri(file.toURI().toString())
                .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
                    putFloat(CONTENT_ASPECT_KEY, 4f / 3f)
                }).build()).build())
            player.prepare(); player.play()
        }
        try {
            compose.setContent { VideoPlayer(player, false, Modifier.fillMaxSize().testTag("picture"), true) }
            compose.waitUntil(10_000) { frames.get() >= 5 }
            compose.waitForIdle()
            val bounds = compose.onNodeWithTag("picture").fetchSemanticsNode().boundsInRoot
            val rootLocation = IntArray(2)
            compose.runOnIdle { context.findViewById<android.view.View>(android.R.id.content).getLocationOnScreen(rootLocation) }
            val shot = instrumentation.uiAutomation.takeScreenshot()!!
            try {
                val x = rootLocation[0] + bounds.center.x.toInt()
                val top = rootLocation[1] + (bounds.top + bounds.height * .03f).toInt()
                val bottom = rootLocation[1] + (bounds.bottom - bounds.height * .03f).toInt()
                val topColor = shot.getPixel(x, top)
                val bottomColor = shot.getPixel(x, bottom)
                assertTrue("Top yellow edge must fill viewport height", Color.red(topColor) > 150 && Color.green(topColor) > 150)
                assertTrue("Bottom cyan edge must remain visible", Color.green(bottomColor) > 150 && Color.blue(bottomColor) > 150)
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "active-picture-fills-landscape.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ArcheryUiReview")
                }
                val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                context.contentResolver.openOutputStream(uri)!!.use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { shot.recycle() }
        } finally {
            compose.runOnUiThread { player.release(); context.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            file.delete()
        }
    }
}
