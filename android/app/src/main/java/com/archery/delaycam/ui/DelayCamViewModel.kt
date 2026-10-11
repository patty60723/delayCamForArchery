package com.archery.delaycam.ui

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.Preview
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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

    private val recordingLifecycle = RecordingLifecycle()
    private val _recordingPhase = MutableStateFlow(RecordingPhase.IDLE)
    val recordingPhase: StateFlow<RecordingPhase> = _recordingPhase.asStateFlow()
    private val _recordingSave = MutableStateFlow(SaveStatus())
    val recordingSave: StateFlow<SaveStatus> = _recordingSave.asStateFlow()
    private val _clipSave = MutableStateFlow(SaveStatus())
    val clipSave: StateFlow<SaveStatus> = _clipSave.asStateFlow()
    private val _mirrorVideo = MutableStateFlow(true)
    val mirrorVideo: StateFlow<Boolean> = _mirrorVideo.asStateFlow()
    private var recordingStartedAtElapsedMs = 0L
    private var recordingHadGaps = false
    private var recordingStoppedInBackground = false
    private var pendingRecordingExport: File? = null
    private var recordingStopTimeout: Job? = null
    private var clipSaveJob: Job? = null
    private var replaySnapshot: ClipSnapshot? = null
    private var failedClipSnapshot: ClipSnapshot? = null
    private var replayAutoReturn = true
    private var closingCaptureSessionId: Long? = null
    private var practiceVisible = false

    private sealed interface RecordingCommand {
        data object Start : RecordingCommand
        data class Append(val segment: SegmentInfo) : RecordingCommand
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
        onGesture = { triggerSave() },
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
                if (playbackState == Player.STATE_ENDED && replayAutoReturn) onReplayFinished()
            }
        })

        // ContinuousSegmentEncoder supplies independently playable MP4
        // segments from one encoder per foreground session. Preserve error
        // recovery for source or decoder failures alongside ENDED handling.
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

    /** Called on the main thread from Activity.onStart(), including first launch. */
    fun onAppForegrounded() {
        val sessionId = captureSession.beginForeground() ?: return
        resetDelayedPlayback()
        gestureEngine.interactionEnabled = practiceVisible
        _warmupRemainingSec.value = settings.value.delaySec
        Log.d(TAG, "app foreground: capture session=$sessionId, warming fresh delay buffer")
        cameraAnalysisExecutor.execute {
            if (!captureSession.isCurrent(sessionId)) return@execute
            val prefix = "capture_${sessionId}_${System.nanoTime()}"
            val encoder = ContinuousSegmentEncoder(
                cacheDir = getApplication<Application>().cacheDir,
                onSegmentFinalized = { segment ->
                    // Finalization posts to main. A stopped encoder can still
                    // have callbacks queued when the next Activity starts.
                    if (captureSession.isCurrent(sessionId)) {
                        onSegmentFinalized(segment)
                    } else if (closingCaptureSessionId == sessionId) {
                        appendRecordingSegment(segment)
                    }
                },
                onStopped = {
                    if (closingCaptureSessionId == sessionId) {
                        closingCaptureSessionId = null
                        finishRecordingAtBoundary()
                    }
                    recordingCommands.trySend(RecordingCommand.Cleanup(prefix, clipSaveJob))
                },
                filePrefix = prefix,
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
        gestureEngine.interactionEnabled = false
        if (recordingLifecycle.phase == RecordingPhase.STARTING ||
            recordingLifecycle.phase == RecordingPhase.RECORDING) {
            recordingStoppedInBackground = true
            requestRecordingStop()
        }
        if (recordingLifecycle.phase == RecordingPhase.STOPPING) closingCaptureSessionId = endingSessionId
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
        segmentEncoder?.onFrame(bitmap)
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
        segmentEncoder?.setRingSize(windowSec + 1)
    }

    private fun onSegmentFinalized(segment: SegmentInfo) {
        segmentBuffer.push(segment)
        if (_appState.value != AppState.REPLAY) {
            delayedPlayback.onSegmentFinalized(segment)
        }
        appendRecordingSegment(segment)
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
                    (delaySec - warmedSec).toInt().coerceAtLeast(0).takeIf { it > 0 }
                }
            }
            delay(300)
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
                recordingCommands.trySend(RecordingCommand.Start)
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

    private fun appendRecordingSegment(segment: SegmentInfo) {
        if (!recordingLifecycle.acceptsSegments()) return
        recordingCommands.trySend(RecordingCommand.Append(segment))
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
                RecordingCommand.Start -> {
                    try {
                        continuousRecordingSession.start()
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
                    }
                }
                RecordingCommand.Stop -> {
                    if (recordingLifecycle.phase != RecordingPhase.STOPPING) continue
                    // Finish a user-requested save even if the Activity is finishing.
                    withContext(NonCancellable) {
                        val displayName = sessionDisplayName()
                        _recordingSave.value = _recordingSave.value.copy(displayName = displayName)
                        try {
                            pendingRecordingExport = continuousRecordingSession.stopToFile()
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
        val uri = withContext(Dispatchers.IO) {
            MediaStoreExport.saveVideoToMediaStore(getApplication(), file, displayName)
        }
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
        gestureEngine.interactionEnabled = visible && captureSession.isForeground()
    }

    fun setMirrorVideo(mirrored: Boolean) { _mirrorVideo.value = mirrored }

    fun markReplayInteraction() { replayAutoReturn = false }

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
        if (playAfterSave) _appState.value = AppState.SAVING
        gestureEngine.locked = true
        val displayName = clipDisplayName()
        _clipSave.value = SaveStatus(SavePhase.SAVING, displayName = displayName, message = "單箭儲存中…")
        clipSaveJob = viewModelScope.launch {
            var snapshot: ClipSnapshot? = null
            try {
                val copied = ClipSnapshot.copy(getApplication<Application>().cacheDir, segments)
                snapshot = copied
                if (playAfterSave && captureSession.isForeground() && _appState.value == AppState.SAVING) {
                    replaySnapshot = copied
                    replayAutoReturn = true
                    replayPlayer.stop()
                    replayPlayer.clearMediaItems()
                    copied.segments.forEach { replayPlayer.addMediaItem(MediaItem.fromUri(it.file.toUri())) }
                    replayPlayer.playbackParameters = PlaybackParameters(currentSettings.slowMoRate)
                    replayPlayer.prepare()
                    replayPlayer.playWhenReady = true
                    livePlayer.pause()
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
                gestureEngine.locked = _appState.value == AppState.REPLAY
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

    fun deleteClip(uri: android.net.Uri, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            try {
                galleryRepository.deleteClip(uri)
                refreshGallery()
                onDeleted()
            } catch (e: Exception) {
                Log.e(TAG, "delete clip failed", e)
                _toast.value = "影片未能刪除，請稍後再試"
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
        CoroutineScope(Dispatchers.IO).launch { continuousRecordingSession.releaseResources() }
        livePlayer.release()
        replayPlayer.release()
        super.onCleared()
    }
}

