package com.archery.delaycam.camera

import android.graphics.Color
import android.os.SystemClock
import android.view.TextureView
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.archery.delaycam.ui.playback.VideoPlayer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Verify buffering, initial delay, visible pixels and leases with the real Media3 player. */
class DelayedPlaybackContinuityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    private fun onPlayerThread(predicate: () -> Boolean): Boolean {
        var result = false
        compose.runOnUiThread { result = predicate() }
        return result
    }

    private fun source(store: ImmutableSegmentStore, start: Long): SegmentInfo {
        val file = store.allocate()
        instrumentation.context.assets.open("clip-fixture.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        store.publish(file)
        return SegmentInfo(file, start, start + 1000)
    }

    @Test(timeout = 120_000) fun preloadsBeforeDelayedStartThenCrossesFiveBoundariesWithoutBlackFrames() {
        val store = ImmutableSegmentStore(context.cacheDir, "continuity_${UUID.randomUUID()}")
        val wallNow = System.currentTimeMillis()
        val firstDueElapsed = SystemClock.elapsedRealtime() + 3000
        val sources = (0 until 8).map { source(store, wallNow - 8000 + it * 1000L) }
        lateinit var player: ExoPlayer
        lateinit var controller: DelayedPlaybackController
        val firstFrame = AtomicLong()
        val transitions = AtomicInteger()
        val prematureEnds = AtomicInteger()
        compose.runOnUiThread {
            player = ExoPlayer.Builder(context).build()
            controller = DelayedPlaybackController(player) { 10_000L }
            player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { firstFrame.compareAndSet(0, SystemClock.elapsedRealtime()) }
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) { transitions.incrementAndGet() }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED && transitions.get() < 8) prematureEnds.incrementAndGet()
                }
            })
        }
        try {
            compose.setContent { VideoPlayer(player, false, Modifier.fillMaxSize()) }
            compose.runOnIdle {
                sources.forEach { assertTrue(controller.onSegmentFinalized(it, store)) }
                assertEquals("All completed sources must preload, not wait on individual timers", 8, player.mediaItemCount)
                assertEquals("Do not prepare/render footage before initial delay", Player.STATE_IDLE, player.playbackState)
                // Duplicates must not create another playlist item or acquire another lease.
                assertTrue(controller.onSegmentFinalized(sources.first(), store))
                assertEquals(8, player.mediaItemCount)
                store.setKeepCount(1)
                assertTrue("Pending first source must remain pinned", sources.first().file.exists())
            }
            compose.waitUntil(12_000) { firstFrame.get() != 0L }
            assertTrue("Initial delay must be retained", firstFrame.get() >= firstDueElapsed - 100)
            var samples = 0
            var blackSamples = 0
            var shutterSamples = 0
            val until = SystemClock.elapsedRealtime() + 10_000
            while (transitions.get() < 6 && SystemClock.elapsedRealtime() < until) {
                compose.runOnUiThread {
                    val views = androidx.test.espresso.util.TreeIterables.breadthFirstViewTraversal(compose.activity.window.decorView)
                    val view = views.filterIsInstance<PlayerView>().first { it.tag == "archery-video-player" }
                    val shutter = view.findViewById<View>(androidx.media3.ui.R.id.exo_shutter)
                    if (shutter?.visibility == View.VISIBLE) shutterSamples++
                    val bitmap = (view.videoSurfaceView as TextureView).getBitmap(32, 32)
                    assertNotNull(bitmap)
                    try {
                        val pixel = bitmap!!.getPixel(16, 16)
                        if (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel) < 60) blackSamples++
                        samples++
                    } finally { bitmap?.recycle() }
                }
                SystemClock.sleep(80)
            }
            assertTrue("Need five real period transitions", transitions.get() >= 6)
            assertTrue("Need ongoing visible-frame sampling", samples >= 15)
            assertEquals("No black encoded/displayed texture frames", 0, blackSamples)
            assertEquals("No visible reset shutter between periods", 0, shutterSamples)
            assertEquals("Preloaded playback must not run out between sources", 0, prematureEnds.get())
            compose.runOnIdle {
                assertNull(player.playerError)
                controller.onSegmentFinalized(source(store, wallNow), store)
                assertTrue("Playlist trims played items", player.currentMediaItemIndex <= 3)
                assertFalse("Played leases are released after trimming", sources.first().file.exists())
            }
            android.util.Log.i("DelayContinuityTest", "transitions=${transitions.get()} samples=$samples black=$blackSamples shutter=$shutterSamples earlyEnds=${prematureEnds.get()}")
        } finally {
            compose.runOnUiThread { controller.reset(); player.release() }
            store.close()
        }
    }

    @Test(timeout = 60_000) fun aRealCaptureGapResumesWhenNextFileArrives() {
        val store = ImmutableSegmentStore(context.cacheDir, "gap_${UUID.randomUUID()}")
        val first = source(store, System.currentTimeMillis() - 1000)
        lateinit var player: ExoPlayer
        lateinit var controller: DelayedPlaybackController
        val frames = AtomicInteger()
        compose.runOnUiThread {
            player = ExoPlayer.Builder(context).build()
            player.setVideoFrameMetadataListener { _, _, _, _ -> frames.incrementAndGet() }
            controller = DelayedPlaybackController(player) { 0L }
        }
        try {
            compose.setContent { VideoPlayer(player, false, Modifier.fillMaxSize()) }
            compose.runOnIdle { assertTrue(controller.onSegmentFinalized(first, store)) }
            compose.waitUntil(12_000) { onPlayerThread { player.playbackState == Player.STATE_ENDED } }
            val before = frames.get()
            val next = source(store, first.endedAtMs)
            compose.runOnIdle { assertTrue(controller.onSegmentFinalized(next, store)) }
            compose.waitUntil(12_000) { onPlayerThread { player.currentMediaItem?.localConfiguration?.uri?.path == next.file.absolutePath && frames.get() >= before + 3 } }
            compose.runOnIdle { assertNull(player.playerError) }
        } finally {
            compose.runOnUiThread { controller.reset(); player.release() }
            store.close()
        }
    }
}
