package com.archery.delaycam.ui

import com.archery.delaycam.camera.playbackItem
import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.Preview
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.archery.delaycam.BuildConfig
import com.archery.delaycam.camera.ImmutableSegmentStore
import com.archery.delaycam.camera.FastResumePlan
import com.archery.delaycam.camera.ClipExporter
import com.archery.delaycam.camera.ClipSnapshot
import com.archery.delaycam.camera.MediaStoreExport
import com.archery.delaycam.camera.RecordingLifecycle
import com.archery.delaycam.camera.RecordingPhase
import com.archery.delaycam.camera.ContinuousRecordingSession
import com.archery.delaycam.camera.ContinuousSegmentEncoder
import com.archery.delaycam.camera.DelayedPlaybackController
import com.archery.delaycam.camera.ForegroundCaptureSession
import com.archery.delaycam.camera.SegmentBuffer
import com.archery.delaycam.camera.SegmentInfo
import com.archery.delaycam.gallery.ClipEntry
import com.archery.delaycam.gallery.GalleryRepository
import com.archery.delaycam.gesture.GestureEngine
import com.archery.delaycam.gesture.GestureAction
import com.archery.delaycam.gesture.GestureScene
import com.archery.delaycam.ui.playback.PlaybackSessionController
import com.archery.delaycam.ui.playback.ReplayReturnPolicy
import com.archery.delaycam.settings.AppSettings
import com.archery.delaycam.settings.SettingsRepository
import com.archery.delaycam.state.AppState
import com.archery.delaycam.state.SavePhase
import com.archery.delaycam.state.SaveStatus
import com.archery.delaycam.util.clipDisplayName
import com.archery.delaycam.util.sessionDisplayName
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.io.File

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
data class ClipDeletionState(val uri: android.net.Uri? = null, val busy: Boolean = false, val error: String? = null)

class DelayCamViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepository = SettingsRepository(application)
    private val galleryRepository = GalleryRepository(application)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    private var pendingSettings: AppSettings? = null
    @Volatile private var hasCameraFrame = false

    private val _appState = MutableStateFlow(AppState.LIVE)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    private val _cameraAspectRatio = MutableStateFlow(3f / 4f)
    val cameraAspectRatio: StateFlow<Float> = _cameraAspectRatio.asStateFlow()

    private val _warmupRemainingSec = MutableStateFlow<Int?>(null)
    val warmupRemainingSec: StateFlow<Int?> = _warmupRemainingSec.asStateFlow()
    private var liveHasRenderedFrame = false
    private var immutableStore: ImmutableSegmentStore? = null
    private var bufferGeneration = 0L
    private var replayBufferGeneration = -1L
    private var appliedDelaySec = AppSettings().delaySec
    private var fastResumeDeadlineMs: Long? = null

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

    private val recordingLifecycle = RecordingLifecycle()
    private val _recordingPhase = MutableStateFlow(RecordingPhase.IDLE)
    val recordingPhase: StateFlow<RecordingPhase> = _recordingPhase.asStateFlow()
    private val _recordingSave = MutableStateFlow(SaveStatus())
    val recordingSave: StateFlow<SaveStatus> = _recordingSave.asStateFlow()
    private data class PendingDeletion(val uri: android.net.Uri, val onDeleted: () -> Unit, val retry: Boolean)
    private var pendingDeletion: PendingDeletion? = null
    private val _deleteApproval = MutableStateFlow<android.content.IntentSender?>(null)
    val deleteApproval: StateFlow<android.content.IntentSender?> = _deleteApproval.asStateFlow()
    private val _clipDeletion = MutableStateFlow(ClipDeletionState())
    val clipDeletion: StateFlow<ClipDeletionState> = _clipDeletion.asStateFlow()

    private val _clipSave = MutableStateFlow(SaveStatus())
    val clipSave: StateFlow<SaveStatus> = _clipSave.asStateFlow()
    private val _mirrorVideo = MutableStateFlow(true)
    val mirrorVideo: StateFlow<Boolean> = _mirrorVideo.asStateFlow()
    private var recordingStartedAtElapsedMs = 0L
    private var recordingHadGaps = false
    private var recordingStoppedInBackground = false
    private var pendingRecordingExport: File? = null
    private var pendingRecordingRanges: List<com.archery.delaycam.gallery.PictureRange> = emptyList()
    private var pendingRecordingAspect: Float? = null
    private var recordingStopTimeout: Job? = null
    private var clipSaveJob: Job? = null
    private var replaySnapshot: ClipSnapshot? = null
    private var failedClipSnapshot: ClipSnapshot? = null
    private val replayReturnPolicy = ReplayReturnPolicy()
    private val _replayReturnRemainingSec = MutableStateFlow<Int?>(null)
    val replayReturnRemainingSec: StateFlow<Int?> = _replayReturnRemainingSec.asStateFlow()
    private var closingCaptureSessionId: Long? = null
    private var practiceVisible = false

    private sealed interface RecordingCommand {
        data class Start(val aspect: Float?) : RecordingCommand
        data class Append(val segment: SegmentInfo, val lease: ImmutableSegmentStore.Lease?) : RecordingCommand
        data object Stop : RecordingCommand
        data class Cleanup(val prefix: String, val shotJob: Job?) : RecordingCommand
    }
    private val recordingCommands = Channel<RecordingCommand>(Channel.UNLIMITED)

    val livePlayer: ExoPlayer = ExoPlayer.Builder(application).build()
    val replayPlayer: ExoPlayer = ExoPlayer.Builder(application).build()

    private val segmentBuffer = SegmentBuffer(maxAgeMs = 10_000L)

    // Camera frames, encoder stop, and encoder replacement share one FIFO
    // executor, so a Surface is never released while an analyzer draws to it.
    val cameraAnalysisExecutor = Executors.newSingleThreadExecutor()
    private val captureSession = ForegroundCaptureSession()
    @Volatile private var segmentEncoder: ContinuousSegmentEncoder? = null
    private var resumeReplayOnForeground = false
    private val delayedPlayback = DelayedPlaybackController(
        exoPlayer = livePlayer,
        getDelaySecMs = { settings.value.delaySec * 1000L },
    )
    private val continuousRecordingSession = ContinuousRecordingSession(application)

    val gestureEngine = GestureEngine(
        application,
        onAction = ::handleGestureAction,
    )

    val replayPlayback = PlaybackSessionController(
        replayPlayer, ::markReplayInteraction,
        onSpeedChange = { rate -> updateSettings { it.copy(slowMoRate = rate) } },
        onFeedback = { _toast.value = it },
    )

    private fun syncGestureScene() {
        gestureEngine.setScene(when {
            !practiceVisible || !captureSession.isForeground() -> GestureScene.DISABLED
            _appState.value == AppState.REPLAY -> GestureScene.REPLAY
            _appState.value == AppState.SAVING -> GestureScene.SAVING
            else -> GestureScene.LIVE
        })
    }

    private fun handleGestureAction(action: GestureAction, scene: GestureScene) {
        if (!practiceVisible || !captureSession.isForeground()) return
        val current = when (_appState.value) {
            AppState.LIVE -> GestureScene.LIVE
            AppState.SAVING -> GestureScene.SAVING
            AppState.REPLAY -> GestureScene.REPLAY
        }
        if (scene != current) return
        when (action) {
            GestureAction.SAVE_CLIP -> if (current == GestureScene.LIVE) triggerSave()
            GestureAction.TOGGLE_RECORD -> toggleContinuousRecording()
            GestureAction.RETURN_TO_PRACTICE -> if (current == GestureScene.REPLAY) {
                _toast.value = "返回練習"
                skipReplay()
            }
            GestureAction.REPLAY_CLIP -> if (current == GestureScene.REPLAY) replayPlayback.restart()
            GestureAction.FASTER -> if (current == GestureScene.REPLAY) replayPlayback.changeSpeed(true)
            GestureAction.SLOWER -> if (current == GestureScene.REPLAY) replayPlayback.changeSpeed(false)
        }
    }

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
    val previewUseCase: Preview = Preview.Builder().setTargetAspectRatio(androidx.camera.core.AspectRatio.RATIO_4_3).build()

    init {
        viewModelScope.launch {
            settingsRepository.settingsFlow.collect { loaded ->
                if (pendingSettings != null && pendingSettings != loaded) return@collect
                pendingSettings = null
                _settings.value = loaded
                applyBufferWindow(loaded)
            }
        }
        viewModelScope.launch { refreshGallery() }
        viewModelScope.launch { warmupTicker() }
        viewModelScope.launch { replayReturnTicker() }
        viewModelScope.launch { _appState.collect { syncGestureScene() } }
        viewModelScope.launch { recordingElapsedTicker() }
        viewModelScope.launch { livePlayerStallWatchdog() }
        viewModelScope.launch { runRecordingCommands() }

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
                if (playbackState == Player.STATE_ENDED && _appState.value == AppState.REPLAY) {
                    replayReturnPolicy.ended(SystemClock.elapsedRealtime())
                }
            }
        })

        // ContinuousSegmentEncoder supplies independently playable MP4
        // segments from one encoder per foreground session. Preserve error
        // recovery for source or decoder failures alongside ENDED handling.
        livePlayer.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (captureSession.isForeground() && livePlayer.mediaItemCount > 0) {
                    liveHasRenderedFrame = true
                    fastResumeDeadlineMs = null
                    _warmupRemainingSec.value = null
                }
            }
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

    /** Called on the main thread from Activity.onStart(), including first launch. */
    fun onAppForegrounded() {
        val sessionId = captureSession.beginForeground() ?: return
        hasCameraFrame = false
        resetDelayedPlayback()
        syncGestureScene()
        _warmupRemainingSec.value = settings.value.delaySec
        Log.d(TAG, "app foreground: capture session=$sessionId, warming fresh delay buffer")
        val prefix = "capture_${sessionId}_${System.nanoTime()}"
        val store = if (BuildConfig.FAST_RESUME_TRIAL)
            ImmutableSegmentStore(getApplication<Application>().cacheDir, prefix) else null
        immutableStore = store
        cameraAnalysisExecutor.execute {
            if (!captureSession.isCurrent(sessionId)) { store?.close(); return@execute }
            val encoder = ContinuousSegmentEncoder(
                cacheDir = getApplication<Application>().cacheDir,
                onSegmentFinalized = { segment ->
                    // Finalization posts to main. A stopped encoder can still
                    // have callbacks queued when the next Activity starts.
                    if (captureSession.isCurrent(sessionId)) {
                        onSegmentFinalized(segment, store)
                    } else if (closingCaptureSessionId == sessionId) {
                        appendRecordingSegment(segment, store)
                    }
                },
                onStopped = {
                    if (closingCaptureSessionId == sessionId) {
                        closingCaptureSessionId = null
                        finishRecordingAtBoundary()
                    }
                    if (store != null) store.close()
                    else recordingCommands.trySend(RecordingCommand.Cleanup(prefix, clipSaveJob))
                },
                filePrefix = prefix,
                immutableStore = store,
            )
            val currentSettings = settings.value
            encoder.setRingSize(
                currentSettings.delaySec + currentSettings.backtrackSec + REACTION_MARGIN_SEC + 1
            )
            segmentEncoder = encoder
            if (!captureSession.markReady(sessionId)) stopSegmentEncoder()
        }
        if (_appState.value == AppState.REPLAY && resumeReplayOnForeground) {
            replayPlayer.playWhenReady = true
        }
        resumeReplayOnForeground = false
    }

    /** Called before CameraX stops receiving frames in Activity.onStop(). */
    fun onAppBackgrounded() {
        val endingSessionId = captureSession.sessionForFrame()
        if (!captureSession.endForeground()) return
        gestureEngine.setScene(GestureScene.DISABLED)
        replayReturnPolicy.suspend()
        _replayReturnRemainingSec.value = null
        if (recordingLifecycle.phase == RecordingPhase.STARTING ||
            recordingLifecycle.phase == RecordingPhase.RECORDING) {
            recordingStoppedInBackground = true
            requestRecordingStop()
        }
        if (recordingLifecycle.phase == RecordingPhase.STOPPING &&
            recordingLifecycle.acceptsSegments() && closingCaptureSessionId == null) {
            closingCaptureSessionId = endingSessionId
        }
        Log.d(TAG, "app background: suspending capture and discarding old delay queue")
        resumeReplayOnForeground = replayPlayer.playWhenReady
        replayPlayer.pause()
        resetDelayedPlayback()
        _warmupRemainingSec.value = settings.value.delaySec
        // Runs after any in-flight analyzer, and before the next session's
        // startup task. Encoder teardown never blocks the Activity thread.
        cameraAnalysisExecutor.execute { stopSegmentEncoder() }
    }

    fun cameraSessionForFrame(): Long? = captureSession.sessionForFrame()

    /** Only called on cameraAnalysisExecutor, with the session captured before conversion. */
    fun onCameraFrame(bitmap: Bitmap, timestampMs: Long, sessionId: Long) {
        if (!captureSession.isCurrent(sessionId)) return
        val aspect = settings.value.captureAspect.ratioFor(bitmap.width, bitmap.height)
        _cameraAspectRatio.value = aspect
        hasCameraFrame = true
        segmentEncoder?.onFrame(bitmap, aspect)
        if (captureSession.isCurrent(sessionId)) {
            gestureEngine.analyzeBitmap(bitmap, timestampMs)
        }
    }

    /** Only called on cameraAnalysisExecutor. */
    private fun stopSegmentEncoder() {
        val encoder = segmentEncoder
        segmentEncoder = null
        try {
            encoder?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "foreground encoder cleanup failed", e)
        }
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
        bufferGeneration++
        fastResumeDeadlineMs = null
        liveHasRenderedFrame = false
        delayedPlayback.reset()
        segmentBuffer.clear()
        _warmupRemainingSec.value = settings.value.delaySec
    }

    fun updateSettings(update: (AppSettings) -> AppSettings) {
        val next = update(_settings.value)
        if (next == _settings.value) return
        if (next.captureAspect != _settings.value.captureAspect && recordingLifecycle.phase != RecordingPhase.IDLE) {
            _toast.value = "請先停止錄影，再切換取景比例"
            return
        }
        pendingSettings = next
        _settings.value = next
        applyBufferWindow(next)
        viewModelScope.launch { settingsRepository.save(next) }
    }

    private fun applyBufferWindow(settings: AppSettings) {
        if (BuildConfig.FAST_RESUME_TRIAL && appliedDelaySec != settings.delaySec) {
            appliedDelaySec = settings.delaySec
            resetDelayedPlayback()
        }
        // Must cover delaySec+backtrackSec, not just whichever is larger: by
        // the time the archer reacts to what the delayed screen shows, the
        // shot they want captured is already delaySec+backtrackSec behind
        // the live camera - see triggerSave()'s doc comment.
        val windowSec = settings.delaySec + settings.backtrackSec + REACTION_MARGIN_SEC
        segmentBuffer.setMaxAgeMs(windowSec * 1000L)
        segmentEncoder?.setRingSize(windowSec + 1)
    }

    private fun onSegmentFinalized(segment: SegmentInfo, store: ImmutableSegmentStore? = immutableStore) {
        segmentBuffer.push(segment)
        if (_appState.value != AppState.REPLAY) {
            if (!delayedPlayback.onSegmentFinalized(segment, store)) {
                Log.w(TAG, "delay source discontinuity: falling back to warmup")
                resetDelayedPlayback()
            }
        }
        appendRecordingSegment(segment, store)
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
            val deadline = fastResumeDeadlineMs
            if (deadline != null && SystemClock.elapsedRealtime() >= deadline) {
                Log.w(TAG, "fast return first-frame timeout: falling back to warmup")
                resetDelayedPlayback()
            }
            val sourcePosition = delayedPlayback.fastSourcePositionMs()
            if (BuildConfig.FAST_RESUME_TRIAL && liveHasRenderedFrame &&
                captureSession.isForeground() && _appState.value == AppState.LIVE && sourcePosition != null &&
                kotlin.math.abs(System.currentTimeMillis() - sourcePosition - settings.value.delaySec * 1000L) > 3_000L) {
                Log.w(TAG, "fast return source drift: falling back to warmup")
                resetDelayedPlayback()
            }
            val playbackState = livePlayer.playbackState
            // ENDED means the playlist ran out while the next segment is being
            // produced. enqueue() resumes from that new item when it arrives.
            // Resetting here would discard the scheduled items and force warmup.
            val watching = captureSession.isForeground() && _appState.value == AppState.LIVE &&
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
                    if (liveHasRenderedFrame) null
                    else (delaySec - warmedSec).toInt().coerceAtLeast(0)
                }
            }
            delay(300)
        }
    }

    private suspend fun replayReturnTicker() {
        while (viewModelScope.isActive) {
            if (_appState.value == AppState.REPLAY && captureSession.isForeground()) {
                val now = SystemClock.elapsedRealtime()
                if (replayPlayer.playbackState == Player.STATE_ENDED) replayReturnPolicy.ended(now)
                _replayReturnRemainingSec.value = replayReturnPolicy.remainingSeconds(now)
                if (replayReturnPolicy.shouldReturn(now)) onReplayFinished()
            } else _replayReturnRemainingSec.value = null
            delay(100)
        }
    }

    private suspend fun recordingElapsedTicker() {
        while (viewModelScope.isActive) {
            if (recordingLifecycle.phase == RecordingPhase.RECORDING) {
                _recordingElapsedSec.value =
                    ((SystemClock.elapsedRealtime() - recordingStartedAtElapsedMs) / 1000).toInt()
            }
            delay(500)
        }
    }

    private fun publishRecordingPhase() {
        _recordingPhase.value = recordingLifecycle.phase
        _isContinuousRecording.value = recordingLifecycle.phase == RecordingPhase.RECORDING ||
            recordingLifecycle.phase == RecordingPhase.STARTING
    }

    fun toggleContinuousRecording() {
        if (!captureSession.isForeground()) return
        when (recordingLifecycle.phase) {
            RecordingPhase.IDLE -> {
                if (pendingRecordingExport != null || _recordingSave.value.phase == SavePhase.SAVING) return
                if (!recordingLifecycle.beginStart()) return
                recordingStartedAtElapsedMs = SystemClock.elapsedRealtime()
                recordingHadGaps = false
                recordingStoppedInBackground = false
                _recordingElapsedSec.value = 0
                _recordingSave.value = SaveStatus()
                publishRecordingPhase()
                val wide = _cameraAspectRatio.value > 1f
                val recordAspect = settings.value.captureAspect.ratioFor(if (wide) 4 else 3, if (wide) 3 else 4)
                recordingCommands.trySend(RecordingCommand.Start(recordAspect.takeIf { hasCameraFrame }))
            }
            RecordingPhase.RECORDING -> requestRecordingStop()
            RecordingPhase.STARTING, RecordingPhase.STOPPING -> Unit
        }
    }

    private fun requestRecordingStop() {
        if (!recordingLifecycle.requestStop(System.currentTimeMillis())) return
        _recordingElapsedSec.value =
            ((SystemClock.elapsedRealtime() - recordingStartedAtElapsedMs) / 1000).toInt()
        _recordingSave.value = SaveStatus(SavePhase.SAVING, message = "正在完成錄影並保存…")
        publishRecordingPhase()
        // Usually the next segment (or encoder shutdown callback) supplies the tail.
        // Missing camera output must not leave a permanent busy state.
        recordingStopTimeout?.cancel()
        recordingStopTimeout = viewModelScope.launch {
            delay(5_000)
            if (recordingLifecycle.phase == RecordingPhase.STOPPING) {
                recordingHadGaps = true
                finishRecordingAtBoundary()
            }
        }
    }

    private fun appendRecordingSegment(segment: SegmentInfo, store: ImmutableSegmentStore? = immutableStore) {
        if (!recordingLifecycle.acceptsSegments()) return
        val lease = store?.acquire(listOf(segment))
        if (store != null && lease == null) {
            recordingHadGaps = true
        } else if (recordingCommands.trySend(RecordingCommand.Append(segment, lease)).isFailure) {
            lease?.close()
        }
        if (recordingLifecycle.reachesStopBoundary(segment.endedAtMs)) finishRecordingAtBoundary()
    }

    private fun finishRecordingAtBoundary() {
        if (!recordingLifecycle.queueStop()) return
        recordingStopTimeout?.cancel()
        recordingCommands.trySend(RecordingCommand.Stop)
    }

    private suspend fun runRecordingCommands() {
        for (command in recordingCommands) {
            when (command) {
                is RecordingCommand.Start -> {
                    try {
                        continuousRecordingSession.start(command.aspect)
                        recordingLifecycle.started()
                        publishRecordingPhase()
                    } catch (e: Exception) {
                        Log.e(TAG, "recording start failed", e)
                        recordingLifecycle.finished()
                        publishRecordingPhase()
                        _recordingSave.value = SaveStatus(SavePhase.FAILED,
                            message = "無法開始錄影，請確認儲存空間後再試")
                    }
                }
                is RecordingCommand.Append -> {
                    try {
                        continuousRecordingSession.appendSegment(command.segment)
                    } catch (e: Exception) {
                        recordingHadGaps = true
                        Log.e(TAG, "recording segment was not appended", e)
                    } finally { command.lease?.close() }
                }
                RecordingCommand.Stop -> {
                    if (recordingLifecycle.phase != RecordingPhase.STOPPING) continue
                    // Finish a user-requested save even if the Activity is finishing.
                    withContext(NonCancellable) {
                        val displayName = sessionDisplayName()
                        _recordingSave.value = _recordingSave.value.copy(displayName = displayName)
                        try {
                            pendingRecordingExport = continuousRecordingSession.stopToFile()
                            pendingRecordingRanges = continuousRecordingSession.presentationRanges()
                            pendingRecordingAspect = continuousRecordingSession.outputAspect
                            exportPendingRecording(displayName)
                        } catch (e: Exception) {
                            Log.e(TAG, "recording finalization/save failed", e)
                            _recordingSave.value = SaveStatus(SavePhase.FAILED,
                                displayName = displayName,
                                message = if (pendingRecordingExport != null)
                                    "錄影儲存失敗，請確認空間後重試"
                                else "錄影未能完成，請重新開始",
                                retryable = pendingRecordingExport != null)
                        } finally {
                            recordingLifecycle.finished()
                            publishRecordingPhase()
                        }
                    }
                }
                is RecordingCommand.Cleanup -> {
                    command.shotJob?.join()
                    withContext(Dispatchers.IO) {
                        getApplication<Application>().cacheDir.listFiles()
                            ?.filter { it.name.startsWith(command.prefix + "_segment_") }
                            ?.forEach { it.delete() }
                    }
                }
            }
        }
    }

    private suspend fun exportPendingRecording(displayName: String) {
        val file = pendingRecordingExport ?: return
        val normalized = com.archery.delaycam.camera.VideoAspectNormalizer.normalize(
            getApplication(), file, pendingRecordingRanges, pendingRecordingAspect)
        val uri = try { withContext(Dispatchers.IO) {
            MediaStoreExport.saveVideoToMediaStore(getApplication(), normalized.file, displayName)
        } } finally { if (normalized.file != file) normalized.file.delete() }
        pendingRecordingExport = null
        withContext(Dispatchers.IO) { file.delete() }
        val message = when {
            recordingHadGaps -> "已保存；有片段未確認，請查看影片"
            recordingStoppedInBackground -> "上一段已保存，錄影已停止"
            else -> "錄影已保存"
        }
        _recordingSave.value = SaveStatus(SavePhase.SAVED, uri, displayName, message)
        refreshGallery()
    }

    fun retryRecordingSave() {
        if (_recordingSave.value.phase != SavePhase.FAILED || pendingRecordingExport == null) return
        val name = _recordingSave.value.displayName
        _recordingSave.value = SaveStatus(SavePhase.SAVING, displayName = name, message = "錄影儲存中…")
        viewModelScope.launch {
            withContext(NonCancellable) {
                try { exportPendingRecording(name) }
                catch (e: Exception) {
                    Log.e(TAG, "recording save retry failed", e)
                    _recordingSave.value = SaveStatus(SavePhase.FAILED, displayName = name,
                        message = "錄影儲存失敗，請確認空間後重試", retryable = true)
                }
            }
        }
    }

    fun setPracticeVisible(visible: Boolean) {
        practiceVisible = visible
        syncGestureScene()
    }

    fun setMirrorVideo(mirrored: Boolean) { _mirrorVideo.value = mirrored }

    fun markReplayInteraction() {
        replayReturnPolicy.interact()
        _replayReturnRemainingSec.value = null
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
    fun triggerSave(playAfterSave: Boolean = true) {
        if (!captureSession.isForeground() || _appState.value != AppState.LIVE ||
            _warmupRemainingSec.value != null ||
            _clipSave.value.phase == SavePhase.SAVING) return
        if (failedClipSnapshot != null) {
            _toast.value = "請先重試保存上一段影片"
            return
        }
        val currentSettings = settings.value
        val now = System.currentTimeMillis()
        val shotReferenceMs = now - currentSettings.delaySec * 1000L
        val segments = segmentBuffer.segmentsCoveringRange(
            shotReferenceMs - currentSettings.backtrackSec * 1000L, now)
        if (segments.isEmpty()) {
            _toast.value = "延遲畫面還在準備，請稍候再存檔"
            return
        }
        val sourceLease = immutableStore?.acquire(segments)
        if (BuildConfig.FAST_RESUME_TRIAL && sourceLease == null) {
            _toast.value = "影片片段已更新，請稍候再存檔"
            return
        }
        if (playAfterSave) _appState.value = AppState.SAVING
        syncGestureScene()
        val displayName = clipDisplayName()
        _clipSave.value = SaveStatus(SavePhase.SAVING, displayName = displayName, message = "單箭儲存中…")
        clipSaveJob = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            var snapshot: ClipSnapshot? = null
            try {
                val copied = try {
                    ClipSnapshot.copy(getApplication<Application>().cacheDir, segments)
                } finally { sourceLease?.close() }
                snapshot = copied
                if (playAfterSave && captureSession.isForeground() && _appState.value == AppState.SAVING) {
                    replaySnapshot = copied
                    replayReturnPolicy.begin()
                    replayPlayer.stop()
                    replayPlayer.clearMediaItems()
                    copied.segments.forEach { replayPlayer.addMediaItem(it.playbackItem()) }
                    replayPlayer.playbackParameters = PlaybackParameters(currentSettings.slowMoRate)
                    replayPlayer.prepare()
                    replayPlayer.playWhenReady = true
                    replayBufferGeneration = bufferGeneration
                    if (BuildConfig.FAST_RESUME_TRIAL) delayedPlayback.reset() else livePlayer.pause()
                    _appState.value = AppState.REPLAY
                } else if (_appState.value == AppState.SAVING) {
                    _appState.value = AppState.LIVE
                }
                exportShot(copied, displayName)
            } catch (e: Exception) {
                Log.e(TAG, "shot snapshot/export failed", e)
                failedClipSnapshot = snapshot
                _clipSave.value = SaveStatus(SavePhase.FAILED, displayName = displayName,
                    message = if (snapshot != null) "單箭儲存失敗，請確認空間後重試"
                    else "單箭未能保存，請確認空間後再存檔", retryable = snapshot != null)
                if (_appState.value == AppState.SAVING) _appState.value = AppState.LIVE
            } finally {
                syncGestureScene()
                val unused = snapshot
                if (unused != null && unused !== replaySnapshot && unused !== failedClipSnapshot) {
                    withContext(Dispatchers.IO) { unused.delete() }
                }
            }
        }
    }

    private suspend fun exportShot(snapshot: ClipSnapshot, displayName: String) {
        val uri = ClipExporter.export(getApplication(), snapshot.segments, displayName)
        if (failedClipSnapshot === snapshot) failedClipSnapshot = null
        _clipSave.value = SaveStatus(SavePhase.SAVED, uri, displayName, "單箭已保存")
        refreshGallery()
    }

    fun retryClipSave() {
        val snapshot = failedClipSnapshot ?: return
        if (_clipSave.value.phase != SavePhase.FAILED) return
        val name = _clipSave.value.displayName
        _clipSave.value = SaveStatus(SavePhase.SAVING, displayName = name, message = "單箭儲存中…")
        clipSaveJob = viewModelScope.launch {
            try { exportShot(snapshot, name) }
            catch (e: Exception) {
                Log.e(TAG, "shot save retry failed", e)
                _clipSave.value = SaveStatus(SavePhase.FAILED, displayName = name,
                    message = "單箭儲存失敗，請確認空間後重試", retryable = true)
            } finally {
                if (snapshot !== failedClipSnapshot && snapshot !== replaySnapshot) {
                    withContext(Dispatchers.IO) { snapshot.delete() }
                }
            }
        }
    }

    /** Explicit return or untouched replay finishing. */
    fun skipReplay() = onReplayFinished()

    private fun onReplayFinished() {
        if (_appState.value != AppState.REPLAY) return
        replayPlayer.stop()
        replayPlayer.clearMediaItems()
        val snapshot = replaySnapshot
        replaySnapshot = null
        val saveJob = clipSaveJob
        if (snapshot != null) viewModelScope.launch {
            saveJob?.join()
            if (snapshot !== failedClipSnapshot) withContext(Dispatchers.IO) { snapshot.delete() }
        }
        if (!tryFastReturn()) resetDelayedPlayback()
        replayReturnPolicy.suspend()
        _replayReturnRemainingSec.value = null
        _appState.value = AppState.LIVE
        syncGestureScene()
    }

    private fun tryFastReturn(): Boolean {
        if (!BuildConfig.FAST_RESUME_TRIAL || !captureSession.isForeground() ||
            replayBufferGeneration != bufferGeneration) return false
        val store = immutableStore ?: return false
        val now = System.currentTimeMillis()
        val plan = FastResumePlan.create(
            segmentBuffer.segmentsCoveringRange(now - settings.value.delaySec * 1000L, now),
            now, settings.value.delaySec * 1000L,
        ) ?: return false
        liveHasRenderedFrame = false
        _warmupRemainingSec.value = 0 // Existing "waiting for first frame" notice, not another full countdown.
        fastResumeDeadlineMs = SystemClock.elapsedRealtime() + 5_000L
        if (!delayedPlayback.resume(plan, store)) return false
        Log.i(TAG, "fast return: items=${plan.segments.size} seek=${plan.positionMs}ms delay=${settings.value.delaySec}s")
        return true
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

    fun deleteClip(uri: android.net.Uri, onDeleted: () -> Unit = {}) {
        if (_clipDeletion.value.busy) return
        _clipDeletion.value = ClipDeletionState(uri, busy = true)
        viewModelScope.launch {
            try {
                galleryRepository.deleteClip(uri)
                completeClipDeletion(uri, onDeleted)
            } catch (e: Exception) {
                Log.e(TAG, "delete clip failed", e)
                val approval = try {
                    if (e is SecurityException && android.os.Build.VERSION.SDK_INT >= 30)
                        android.provider.MediaStore.createDeleteRequest(getApplication<Application>().contentResolver, listOf(uri)).intentSender
                    else if (android.os.Build.VERSION.SDK_INT == 29 && e is android.app.RecoverableSecurityException)
                        e.userAction.actionIntent.intentSender else null
                } catch (_: Exception) { null }
                if (approval != null) {
                    pendingDeletion = PendingDeletion(uri, onDeleted, android.os.Build.VERSION.SDK_INT == 29)
                    _deleteApproval.value = approval
                } else _clipDeletion.value = ClipDeletionState(uri, error = "影片未能刪除，請稍後重試。")
            }
        }
    }

    private suspend fun completeClipDeletion(uri: android.net.Uri, onDeleted: () -> Unit) {
        if (_clipSave.value.uri == uri) _clipSave.value = SaveStatus()
        if (_recordingSave.value.uri == uri) _recordingSave.value = SaveStatus()
        com.archery.delaycam.gallery.ClipPresentationStore(getApplication()).remove(uri)
        _clipDeletion.value = ClipDeletionState()
        refreshGallery()
        _toast.value = "影片已刪除"
        onDeleted()
    }

    fun onDeleteApprovalResult(granted: Boolean) {
        val pending = pendingDeletion ?: return
        pendingDeletion = null
        _deleteApproval.value = null
        if (!granted) {
            _clipDeletion.value = ClipDeletionState(pending.uri, error = "已取消刪除，影片仍保留。")
        } else if (pending.retry) {
            _clipDeletion.value = ClipDeletionState()
            deleteClip(pending.uri, pending.onDeleted)
        } else viewModelScope.launch {
            try {
                check(!galleryRepository.clipExists(pending.uri)) { "Video still exists" }
                completeClipDeletion(pending.uri, pending.onDeleted)
            } catch (failure: Exception) {
                _clipDeletion.value = ClipDeletionState(pending.uri, error = "影片未能刪除，請重試。")
            }
        }
    }

    fun consumeToast() {
        _toast.value = null
    }

    override fun onCleared() {
        captureSession.close()
        cameraAnalysisExecutor.execute {
            stopSegmentEncoder()
            gestureEngine.close()
        }
        cameraAnalysisExecutor.shutdown()
        recordingCommands.close()
        while (true) {
            val pending = recordingCommands.tryReceive().getOrNull() ?: break
            if (pending is RecordingCommand.Append) pending.lease?.close()
        }
        delayedPlayback.reset()
        CoroutineScope(Dispatchers.IO).launch { continuousRecordingSession.releaseResources() }
        livePlayer.release()
        replayPlayer.release()
        super.onCleared()
    }
}

