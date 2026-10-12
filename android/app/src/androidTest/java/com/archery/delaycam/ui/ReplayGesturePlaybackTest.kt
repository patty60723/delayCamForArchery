package com.archery.delaycam.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.archery.delaycam.gesture.*
import com.archery.delaycam.ui.playback.*
import com.archery.delaycam.ui.replay.ReplayReturnNotice
import com.archery.delaycam.ui.theme.DelayCamTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ReplayGesturePlaybackTest {
    @get:Rule val compose = createComposeRule()

    private fun player(): ExoPlayer {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val file = File(instrumentation.targetContext.cacheDir, "gesture-playback-fixture.mp4")
        instrumentation.context.assets.open("clip-fixture.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        lateinit var player: ExoPlayer
        compose.runOnUiThread {
            player = ExoPlayer.Builder(instrumentation.targetContext).build()
            player.setMediaItems(List(2) { MediaItem.fromUri(file.toUri()) })
            player.playbackParameters = PlaybackParameters(0.5f)
            player.prepare()
        }
        return player
    }

    private fun pulse(gate: GestureCommandGate, pose: String, t: Long): GestureAction {
        gate.evaluate(null, 0f, t)
        gate.evaluate(null, 0f, t + 300)
        gate.evaluate(pose, 0.9f, t + 400)
        return gate.evaluate(pose, 0.9f, t + 1000)!!.action
    }

    @Test fun gestureSpeedAndReplayUseExistingPlayerAndCancelReturn() {
        val player = player()
        var remaining by mutableStateOf<Int?>(2)
        var interactions = 0
        var feedback by mutableStateOf("")
        val frameRendered = AtomicBoolean()
        lateinit var controller: PlaybackSessionController
        compose.runOnUiThread {
            player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { frameRendered.set(true) }
            })
            controller = PlaybackSessionController(player, { interactions++; remaining = null }, {}, { feedback = it })
        }
        try {
            compose.setContent { DelayCamTheme { Column {
                VideoPlayer(player, true, Modifier.fillMaxWidth().height(180.dp))
                PlaybackControls(player, true, {}, {}, controller = controller)
                ReplayReturnNotice(remaining)
                Text(feedback)
            } } }
            val g = GestureCommandGate(); g.setScene(GestureScene.REPLAY)
            compose.runOnIdle {
                assertEquals(GestureAction.FASTER, pulse(g, "Pointing_Up", 0))
                controller.changeSpeed(true)
                assertEquals(1f, player.playbackParameters.speed, 0f)
                assertEquals(GestureAction.SLOWER, pulse(g, "Victory", 2000))
                controller.changeSpeed(false)
                assertEquals(GestureAction.SLOWER, pulse(g, "Victory", 4000))
                controller.changeSpeed(false)
                assertEquals(0.25f, player.playbackParameters.speed, 0f)
                player.seekTo(1, 300L)
                assertEquals(GestureAction.REPLAY_CLIP, pulse(g, "Open_Palm", 6000))
                assertTrue(controller.restart())
                assertEquals(0, player.currentMediaItemIndex)
                assertEquals(0.25f, player.playbackParameters.speed, 0f)
                assertEquals(2, player.mediaItemCount)
                assertTrue(player.playWhenReady)
            }
            compose.waitUntil(10_000) { frameRendered.get() }
            compose.onNodeWithText("0.25×").assertIsSelected()
            compose.onNodeWithText("已重播").assertIsDisplayed()
            compose.onNodeWithText("2 秒後返回練習 · 五指可重播").assertDoesNotExist()
            compose.onNodeWithText("重播").performClick()
            compose.runOnIdle { assertEquals(5, interactions); assertEquals(2, player.mediaItemCount) }
        } finally { compose.runOnUiThread { player.release() } }
    }

    @Test fun speedBoundsDoNotCycleAndCountdownShowsItsRemainingTime() {
        val player = player()
        var message by mutableStateOf("")
        var remaining by mutableStateOf<Int?>(2)
        lateinit var controller: PlaybackSessionController
        compose.runOnUiThread { controller = PlaybackSessionController(player, onFeedback = { message = it }) }
        try {
            compose.setContent { DelayCamTheme { Column {
                PlaybackControls(player, false, {}, {}, controller = controller)
                ReplayReturnNotice(remaining)
                Text(message)
            } } }
            compose.runOnIdle { controller.selectSpeed(1f); controller.changeSpeed(true) }
            compose.onNodeWithText("已是最快 1×").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(1f, player.playbackParameters.speed, 0f)
                controller.selectSpeed(0.25f); controller.changeSpeed(false)
                remaining = 1
            }
            compose.onNodeWithText("已是最慢 0.25×").assertIsDisplayed()
            compose.onNodeWithText("1 秒後返回練習 · 五指可重播").assertIsDisplayed()
            compose.runOnIdle { assertEquals(0.25f, player.playbackParameters.speed, 0f); remaining = null }
            compose.onNodeWithText("1 秒後返回練習 · 五指可重播").assertDoesNotExist()
        } finally { compose.runOnUiThread { player.release() } }
    }
}
