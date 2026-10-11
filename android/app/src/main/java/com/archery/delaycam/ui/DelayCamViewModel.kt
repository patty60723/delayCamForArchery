package com.archery.delaycam.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.Preview
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.archery.delaycam.camera.ClipExporter
import com.archery.delaycam.camera.ContinuousRecordingSession
import com.archery.delaycam.camera.ContinuousSegmentEncoder
import com.archery.delaycam.camera.DelayedPlaybackController
import com.archery.delaycam.camera.SegmentBuffer
import com.archery.delaycam.camera.SegmentInfo
import com.archery.delaycam.gallery.ClipEntry
import com.archery.delaycam.gallery.GalleryRepository
import com.archery.delaycam.gesture.GestureEngine
import com.archery.delaycam.settings.AppSettings
import com.archery.delaycam.settings.SettingsRepository
import com.archery.delaycam.state.AppState
import com.archery.delaycam.util.clipDisplayName
import com.archery.delaycam.util.sessionDisplayName
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Extra history kept on top of delaySec+backtrackSec, as slack for the time
// it actually takes an archer to notice+react+gesture after watching their
// shot on the delayed screen - see triggerSave()'s doc comment.
private const val REACTION_MARGIN_SEC = 5
private const val TAG = "DelayCamViewModel"
private const val STALL_THRESHOLD_MS = 3000L

/**
 * Central orchestrator - the native counterpart of the web prototype's
 * app.js. Owns the rolling buffer, the two ExoPlayer instances (delayed
 * live mirror + replay), the gesture engine, and the LIVE/SAVING/REPLAY
 * state machine (spec 2.2: new triggers are ignored outside LIVE).
 */
class DelayCamViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val galleryRepository = GalleryRepository(application)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _appState = MutableStateFlow(AppState.LIVE)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    private val _warmupRemainingSec = MutableStateFlow<Int?>(null)
    val warmupRemainingSec: StateFlow<Int?> = _warmupRemainingSec.asStateFlow()

    private val _gestureStatus = MutableStateFlow("手勢辨識初始化中…")
    val gestureStatus: StateFlow<String> = _gestureStatus.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _galleryClips = MutableStateFlow<List<ClipEntry>>(emptyList())
    val galleryClips: StateFlow<List<ClipEntry>> = _galleryClips.asStateFlow()

    private val _isContinuousRecording = MutableStateFlow(false)
    val isContinuousRecording: StateFlow<Boolean> = _isContinuousRecording.asStateFlow()

    private val _recordingElapsedSec = MutableStateFlow(0)
    val recordingElapsedSec: StateFlow<Int> = _recordingElapsedSec.asStateFlow()

    val livePlayer: ExoPlayer = ExoPlayer.Builder(application).build()
    val replayPlayer: ExoPlayer = ExoPlayer.Builder(application).build()

    private val segmentBuffer = SegmentBuffer(maxAgeMs = 10_000L)

    /**
     * MainActivity calls onFrame() on this directly from its ImageAnalysis
     * analyzer (the same one gesture detection uses) with each frame
     * already converted to a Bitmap - see ContinuousSegmentEncoder's doc
     * comment for why segment production moved off CameraX's Recorder,
     * and why it's fed this way rather than its own dedicated camera
     * stream.
     */
    val segmentEncoder = ContinuousSegmentEncoder(
        cacheDir = application.cacheDir,
        onSegmentFinalized = ::onSegmentFinalized,
    )
    private val delayedPlayback = DelayedPlaybackController(
        exoPlayer = livePlayer,
        getDelaySecMs = { settings.value.delaySec * 1000L },
    )
    private val continuousRecordingSession = ContinuousRecordingSession(application)

    val gestureEngine = GestureEngine(
        application,
        onGesture = ::triggerSave,
        onToggleRecord = ::toggleContinuousRecording,
    )

    /**
     * Persistent Preview use case for the PiP window. Lives here (not in
     * LiveScreen) so MainActivity can include it in ONE Activity-scoped
     * CameraX bind call that survives Compose navigation - LiveScreen just
     * points its `surfaceProvider` at whatever PreviewView it currently has
     * when visible; VideoCapture/ImageAnalysis keep running the rolling
     * buffer and gesture detection in the background on Settings/Gallery
     * too, matching the web prototype's "buffer runs regardless of screen"
     * behaviour.
     */
    val previewUseCase: Preview = Preview.Builder().build()

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { loaded ->
                _settings.value = loaded
                applyBufferWindow(loaded)
            }
        }
        viewModelScope.launch { refreshGallery() }
        viewModelScope.launch { warmupTicker() }
        viewModelScope.launch { recordingElapsedTicker() }
        viewModelScope.launch { livePlayerStallWatchdog() }

        // Model load happens once here (not per-screen-visit) so navigating
        // back to Live doesn't re-init and leak a second recognizer.
        viewModelScope.launch {
            withContext(Dispatchers.Default) { gestureEngine.init() }
            _gestureStatus.value = if (gestureEngine.available) {
                "手勢辨識: 已啟用"
            } else {
                "手勢辨識未啟用(用手動按鈕)"
            }
        }

        replayPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onReplayFinished()
            }
        })

        // livePlayer's playlist is a running sequence of independently-
        // recorded 1s segment files (see DelayedPlaybackController) -
        // consecutive segments come from separate CameraX Recorder
        // sessions and can carry slightly different codec init data at
        // their boundary, which is a known risk (see that class's doc
        // comment). With no listener at all, a decode error there used to
        // just silently stop the player - the delayed view would play the
        // first segment and then go permanently black with nothing logged.
        // This at least surfaces the error and self-heals: reset the
        // player, and the next already-scheduled segments (the buffer
        // keeps recording/scheduling regardless) repopulate the playlist
        // within about a second, so the symptom becomes a brief blip
        // instead of a dead screen.
        livePlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                Log.d(TAG, "livePlayer state=$playbackState item=${livePlayer.currentMediaItem?.mediaId} " +
                    "position=${livePlayer.currentPosition}ms queued=${livePlayer.mediaItemCount}")
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "livePlayer error, resetting delayed playback", error)
                resetDelayedPlayback()
            }
        })
    }

    /**
     * Confirmed on-device (logcat) this was the missing piece: the error
     * listener and stall watchdog were only resetting livePlayer itself
     * (stop + clearMediaItems), not DelayedPlaybackController's own
     * bookkeeping - its `scheduled` dedup set (keyed by the ring buffer's
     * reused file paths) and pending Handler callbacks from before the
     * reset stayed alive, so a new segment landing on a path that still
     * had a stale "scheduled" entry was silently dropped, and a stale
     * callback could fire pointing at a file the ring buffer had already
     * overwritten by the time it ran. That's why livePlayer kept getting
     * stuck at position 0 after every reset instead of recovering: nothing
     * was actually reaching it. delayedPlayback.reset() already clears all
     * of that (it was written for the REPLAY->LIVE transition) - routing
     * both recovery paths through it keeps the two objects' state in sync.
     * segmentBuffer.clear() alongside it so the warmup overlay reappears
     * instead of leaving a bare black screen until the next segment lands.
     */
    private fun resetDelayedPlayback() {
        delayedPlayback.reset()
        segmentBuffer.clear()
    }

    fun updateSettings(update: (AppSettings) -> AppSettings) {
        val next = update(_settings.value)
        _settings.value = next
        applyBufferWindow(next)
        viewModelScope.launch { settingsRepository.save(next) }
    }

    private fun applyBufferWindow(settings: AppSettings) {
        // Must cover delaySec+backtrackSec, not just whichever is larger: by
        // the time the archer reacts to what the delayed screen shows, the
        // shot they want captured is already delaySec+backtrackSec behind
        // the live camera - see triggerSave()'s doc comment.
        val windowSec = settings.delaySec + settings.backtrackSec + REACTION_MARGIN_SEC
        segmentBuffer.setMaxAgeMs(windowSec * 1000L)
        segmentEncoder.setRingSize(windowSec + 1)
    }

    private fun onSegmentFinalized(segment: SegmentInfo) {
        segmentBuffer.push(segment)
        if (_appState.value == AppState.LIVE) {
            delayedPlayback.onSegmentFinalized(segment)
        }
        if (_isContinuousRecording.value) {
            viewModelScope.launch { continuousRecordingSession.appendSegment(segment) }
        }
    }

    /**
     * Recover if playback stops progressing in READY or BUFFERING without
     * raising a player error. Track the media item as well as its relative
     * position, since a segment transition can reset the position to zero.
     * ENDED is handled by DelayedPlaybackController when the next segment
     * arrives, without clearing the queue or restarting warmup.
     */
    private suspend fun livePlayerStallWatchdog() {
        var lastMediaItemId: String? = null
        var lastPositionMs = -1L
        var lastChangedAtMs = SystemClock.elapsedRealtime()
        while (viewModelScope.isActive) {
            delay(1000)
            val playbackState = livePlayer.playbackState
            // ENDED means the playlist ran out while the next segment is being
            // produced. enqueue() resumes from that new item when it arrives.
            // Resetting here would discard the scheduled items and force warmup.
            val watching = _appState.value == AppState.LIVE &&
                livePlayer.mediaItemCount > 0 && livePlayer.playWhenReady &&
                (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)
            if (!watching) {
                lastMediaItemId = null
                lastPositionMs = -1L
                lastChangedAtMs = SystemClock.elapsedRealtime()
                continue
            }
            val mediaItemId = livePlayer.currentMediaItem?.mediaId
            val position = livePlayer.currentPosition
            val now = SystemClock.elapsedRealtime()
            // currentPosition is relative to each item. A new segment can have
            // the same sampled position as its predecessor and still be playing.
            if (mediaItemId != lastMediaItemId || position != lastPositionMs) {
                lastMediaItemId = mediaItemId
                lastPositionMs = position
                lastChangedAtMs = now
            } else if (now - lastChangedAtMs > STALL_THRESHOLD_MS) {
                Log.w(TAG, "livePlayer stalled: state=$playbackState item=$mediaItemId position=${position}ms " +
                    "buffered=${livePlayer.bufferedPosition}ms queued=${livePlayer.mediaItemCount}, resetting")
                resetDelayedPlayback()
                lastMediaItemId = null
                lastPositionMs = -1L
                lastChangedAtMs = now
            }
        }
    }

    private suspend fun warmupTicker() {
        while (viewModelScope.isActive) {
            if (_appState.value == AppState.LIVE) {
                val oldest = segmentBuffer.oldestStartMs()
                val delaySec = settings.value.delaySec
                _warmupRemainingSec.value = if (oldest == null) {
                    delaySec
                } else {
                    val warmedSec = (System.currentTimeMillis() - oldest) / 1000
                    (delaySec - warmedSec).toInt().coerceAtLeast(0).takeIf { it > 0 }
                }
            }
            delay(300)
        }
    }

    private suspend fun recordingElapsedTicker() {
        while (viewModelScope.isActive) {
            if (_isContinuousRecording.value) {
                _recordingElapsedSec.value = (continuousRecordingSession.elapsedMs / 1000).toInt()
            }
            delay(1000)
        }
    }

    /**
     * Manual button and gesture (Thumb_Up) trigger share this one path.
     * "Just film the whole session" alternative to per-shot save - added on
     * user feedback, ported from the web prototype's app.js. Deliberately
     * independent of the LIVE/SAVING/REPLAY state machine (no appState
     * guard here, unlike triggerSave): starting/stopping a session recording
     * should keep working through per-shot saves and replays.
     */
    fun toggleContinuousRecording() {
        if (_isContinuousRecording.value) {
            viewModelScope.launch {
                val displayName = sessionDisplayName()
                try {
                    continuousRecordingSession.stop(displayName)
                    _toast.value = "已存檔:$displayName"
                    refreshGallery()
                } catch (e: Exception) {
                    Log.e(TAG, "continuous recording stop/save failed", e)
                    _toast.value = "存檔失敗:${e.message ?: "未知錯誤,請確認儲存空間是否足夠"}"
                } finally {
                    _isContinuousRecording.value = false
                    _recordingElapsedSec.value = 0
                }
            }
        } else {
            viewModelScope.launch {
                try {
                    continuousRecordingSession.start()
                    _isContinuousRecording.value = true
                    _toast.value = "開始連續錄影"
                } catch (e: Exception) {
                    Log.e(TAG, "continuous recording start failed", e)
                    _toast.value = "無法開始錄影:${e.message ?: "未知錯誤"}"
                }
            }
        }
    }

    /**
     * Manual button and gesture trigger share this one path (spec 3.1).
     *
     * backtrackSec is measured from what's currently ON THE DELAYED SCREEN
     * (now - delaySec), not from the raw "now" the trigger fires at. An
     * archer decides to save AFTER watching their shot play out on the
     * delay - by the time they react and gesture, real time has already
     * moved on by delaySec (to see it) plus however long they took to
     * react, so anchoring to raw "now" silently lost the shot itself once
     * that reaction time exceeded backtrackSec - delaySec (trivially true
     * whenever delaySec >= backtrackSec, but also just whenever reacting
     * took a couple of seconds). The saved/replayed clip still runs through
     * to the actual present moment (now), just starting further back.
     */
    fun triggerSave() {
        // Diagnostic for "Open_Palm gesture never seems to do anything":
        // GestureEngine posts here whenever it fires, so if this log line
        // never shows up at all, the gesture itself isn't being recognized
        // (a GestureEngine-side detection/threshold issue, not this
        // function). If it does show up but nothing visibly happens, one of
        // the two guards below is the cause - logging exactly which.
        Log.d(TAG, "triggerSave() called, appState=${_appState.value}")
        if (_appState.value != AppState.LIVE) {
            Log.d(TAG, "triggerSave() ignored: appState is not LIVE")
            return
        }
        val currentSettings = settings.value
        val now = System.currentTimeMillis()
        val shotReferenceMs = now - currentSettings.delaySec * 1000L
        val segments = segmentBuffer.segmentsCoveringRange(shotReferenceMs - currentSettings.backtrackSec * 1000L, now)
        if (segments.isEmpty()) {
            Log.d(TAG, "triggerSave() ignored: segmentBuffer had nothing covering the backtrack window")
            _toast.value = "緩衝畫面還不夠,請再等一下"
            return
        }

        _appState.value = AppState.SAVING
        gestureEngine.locked = true

        // Background export for the gallery - the on-screen replay does not wait on this
        // (see ClipExporter's doc comment for the concatenation caveats).
        val displayName = clipDisplayName()
        viewModelScope.launch {
            try {
                ClipExporter.export(getApplication(), segments, displayName)
                _toast.value = "已存檔:$displayName"
                refreshGallery()
            } catch (e: Exception) {
                Log.e(TAG, "per-shot backtrack save failed", e)
                _toast.value = "存檔失敗:${e.message ?: "未知錯誤,請確認儲存空間是否足夠"}"
            }
        }

        // Instant replay straight off the raw segment files at the chosen slow-mo speed -
        // no need to wait for the export above to finish.
        replayPlayer.stop()
        replayPlayer.clearMediaItems()
        segments.forEach { replayPlayer.addMediaItem(MediaItem.fromUri(it.file.toUri())) }
        replayPlayer.playbackParameters = PlaybackParameters(currentSettings.slowMoRate)
        replayPlayer.prepare()
        replayPlayer.playWhenReady = true
        _appState.value = AppState.REPLAY
    }

    /** "立即返回" button. */
    fun skipReplay() = onReplayFinished()

    private fun onReplayFinished() {
        if (_appState.value != AppState.REPLAY) return
        replayPlayer.stop()
        replayPlayer.clearMediaItems()
        // The backlog scheduled during the replay covers a window the archer
        // already watched - drop it and re-warm-up rather than dumping it in.
        delayedPlayback.reset()
        segmentBuffer.clear()
        gestureEngine.locked = false
        _appState.value = AppState.LIVE
    }

    suspend fun refreshGallery() {
        _galleryClips.value = try {
            galleryRepository.listClips()
        } catch (e: Exception) {
            // MediaStore query failing (e.g. a transient content-provider
            // hiccup) used to propagate straight out of GalleryScreen's
            // LaunchedEffect and crash the whole app on opening Gallery.
            Log.e(TAG, "refreshGallery failed", e)
            emptyList()
        }
    }

    fun deleteClip(uri: android.net.Uri) {
        viewModelScope.launch {
            galleryRepository.deleteClip(uri)
            refreshGallery()
        }
    }

    fun consumeToast() {
        _toast.value = null
    }

    override fun onCleared() {
        segmentEncoder.stop()
        gestureEngine.close()
        continuousRecordingSession.releaseImmediate()
        livePlayer.release()
        replayPlayer.release()
        super.onCleared()
    }
}
