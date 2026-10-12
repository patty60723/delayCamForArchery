package com.archery.delaycam.camera

import android.app.Application
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.archery.delaycam.BuildConfig
import com.archery.delaycam.state.AppState
import com.archery.delaycam.ui.DelayCamViewModel
import com.archery.delaycam.ui.VideoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Real Media3 decode/seek plus ViewModel return/lifecycle integration, using H.264 files. */
class FastReturnPlaybackTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun segment(store: ImmutableSegmentStore, start: Long): SegmentInfo {
        val file = store.allocate()
        instrumentation.context.assets.open("clip-fixture.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
        store.publish(file)
        return SegmentInfo(file, start, start + 1_000)
    }
    private fun store() = ImmutableSegmentStore(context.cacheDir, "return_test_${UUID.randomUUID()}")

    @Test fun freshPlaylistSeeksRendersAndSurvivesSourceEviction() {
        val store = store()
        lateinit var player: ExoPlayer
        lateinit var controller: DelayedPlaybackController
        val rendered = AtomicBoolean()
        val sources = (0..7).map { segment(store, it * 1000L) }
        compose.runOnUiThread {
            player = ExoPlayer.Builder(context).build()
            controller = DelayedPlaybackController(player) { 5_000L }
            player.addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { rendered.set(true) }
            })
        }
        try {
            compose.setContent { VideoPlayer(player, true, Modifier.fillMaxSize()) }
            compose.runOnIdle {
                val plan = FastResumePlan.create(sources, 8_500, 5_000)!!
                assertTrue(controller.resume(plan, store))
                assertEquals(5, player.mediaItemCount)
                assertEquals(500L, player.currentPosition)
                store.setKeepCount(2)
                assertTrue(sources[3].file.exists())
            }
            compose.waitUntil(10_000) { rendered.get() }
            compose.runOnIdle {
                val next = segment(store, 8_000)
                assertTrue(controller.onSegmentFinalized(next, store))
                assertNull(player.playerError)
                // A missing interval must request safe warmup, not append a future jump.
                assertFalse(controller.onSegmentFinalized(segment(store, 11_000), store))
                controller.reset()
                assertFalse(sources[3].file.exists())
            }
        } finally {
            compose.runOnUiThread { controller.reset(); player.release() }
            store.close()
        }
    }

    @Test fun resetCancelsOldTimersAndForeignSessionCannotResume() {
        val store = store(); val other = store()
        lateinit var player: ExoPlayer
        lateinit var controller: DelayedPlaybackController
        val old = segment(store, System.currentTimeMillis() - 1_000)
        compose.runOnUiThread {
            player = ExoPlayer.Builder(context).build()
            controller = DelayedPlaybackController(player) { 500L }
        }
        try {
            compose.runOnIdle {
                assertTrue(controller.onSegmentFinalized(old, store))
                controller.reset()
                assertFalse(controller.resume(FastResumePlan(listOf(old), 0), other))
                store.close()
                assertFalse(old.file.exists())
            }
            val until = SystemClock.elapsedRealtime() + 900L
            compose.waitUntil(3_000) { SystemClock.elapsedRealtime() >= until }
            compose.runOnIdle { assertEquals(0, player.mediaItemCount) }
        } finally { compose.runOnUiThread { controller.reset(); player.release() }; store.close(); other.close() }
    }

    private fun field(vm: DelayCamViewModel, name: String): Any? =
        DelayCamViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm)
    private fun set(vm: DelayCamViewModel, name: String, value: Any) =
        DelayCamViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)

    @Suppress("UNCHECKED_CAST")
    private fun seedReplay(vm: DelayCamViewModel, store: ImmutableSegmentStore) {
        val capture = field(vm, "captureSession") as ForegroundCaptureSession
        capture.beginForeground()?.let { capture.markReady(it) }
        set(vm, "immutableStore", store)
        set(vm, "replayBufferGeneration", field(vm, "bufferGeneration")!!)
        val buffer = field(vm, "segmentBuffer") as SegmentBuffer
        buffer.clear()
        val now = System.currentTimeMillis()
        val delay = vm.settings.value.delaySec
        store.setKeepCount(delay + 5)
        (0 until delay + 3).forEach { buffer.push(segment(store, now - (delay + 3 - it) * 1_000L)) }
        (field(vm, "_appState") as MutableStateFlow<AppState>).value = AppState.REPLAY
    }

    @Test fun viewModelReturnShowsFirstFrameWithoutClearingValidBufferAndKeepsRecording() {
        assumeTrue(BuildConfig.FAST_RESUME_TRIAL)
        val owner = ViewModelStore()
        val store = store()
        lateinit var vm: DelayCamViewModel
        compose.runOnUiThread {
            vm = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))
                .get(DelayCamViewModel::class.java)
        }
        try {
            compose.setContent { VideoPlayer(vm.livePlayer, true, Modifier.fillMaxSize()) }
            compose.runOnIdle {
                seedReplay(vm, store)
                vm.toggleContinuousRecording()
                vm.skipReplay()
                assertEquals(AppState.LIVE, vm.appState.value)
                assertTrue(vm.livePlayer.mediaItemCount > 0)
                assertEquals(0, vm.warmupRemainingSec.value)
                assertTrue(vm.isContinuousRecording.value)
                assertNotNull((field(vm, "segmentBuffer") as SegmentBuffer).oldestStartMs())
            }
            compose.waitUntil(10_000) { vm.warmupRemainingSec.value == null }
            compose.runOnIdle { assertTrue(vm.isContinuousRecording.value); assertNull(vm.livePlayer.playerError) }
        } finally { compose.runOnUiThread { owner.clear() }; store.close() }
    }

    @Test fun backgroundOrDelayChangeDuringReplayRequiresFreshWarmup() {
        assumeTrue(BuildConfig.FAST_RESUME_TRIAL)
        val owner = ViewModelStore()
        val firstStore = store(); val secondStore = store()
        lateinit var vm: DelayCamViewModel
        compose.runOnUiThread {
            vm = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory(context.applicationContext as Application))
                .get(DelayCamViewModel::class.java)
        }
        try {
            compose.runOnIdle {
                seedReplay(vm, firstStore)
                vm.onAppBackgrounded()
                vm.skipReplay()
                assertEquals(0, vm.livePlayer.mediaItemCount)
                assertEquals(vm.settings.value.delaySec, vm.warmupRemainingSec.value)
                seedReplay(vm, secondStore)
                val settings = vm.settings.value.copy(delaySec = if (vm.settings.value.delaySec == 6) 5 else 6)
                vm.updateSettings { settings }
                vm.skipReplay()
                assertEquals(0, vm.livePlayer.mediaItemCount)
                assertEquals(settings.delaySec, vm.warmupRemainingSec.value)
            }
        } finally { compose.runOnUiThread { owner.clear() }; firstStore.close(); secondStore.close() }
    }
}
