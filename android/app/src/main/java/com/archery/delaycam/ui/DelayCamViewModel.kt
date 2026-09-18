package com.archery.delaycam.ui

import android.app.Application
import androidx.camera.core.Preview
import androidx.camera.video.Recorder
import androidx.camera.video.VideoCapture
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.archery.delaycam.camera.ClipExporter
import com.archery.delaycam.camera.DelayedPlaybackController
import com.archery.delaycam.camera.RollingSegmentRecorder
import com.archery.delaycam.camera.SegmentBuffer
import com.archery.delaycam.camera.SegmentInfo
import com.archery.delaycam.gallery.ClipEntry
import com.archery.delaycam.gallery.GalleryRepository
import com.archery.delaycam.gesture.GestureEngine
import com.archery.delaycam.settings.AppSettings
import com.archery.delaycam.settings.SettingsRepository
import com.archery.delaycam.state.AppState
import com.archery.delaycam.util.clipDisplayName
import androidx.media3.common.MediaItem
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

private const val MARGIN_SEC = 2 // extra history kept beyond what delay/backtrack currently need

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

    val livePlayer: ExoPlayer = ExoPlayer.Builder(application).build()
    val replayPlayer: ExoPlayer = ExoPlayer.Builder(application).build()

    private val segmentBuffer = SegmentBuffer(maxAgeMs = 10_000L)
    private val rollingRecorder = RollingSegmentRecorder(
        context = application,
        cacheDir = application.cacheDir,
        onSegmentFinalized = ::onSegmentFinalized,
    )
    private val delayedPlayback = DelayedPlaybackController(
        exoPlayer = livePlayer,
        getDelaySecMs = { settings.value.delaySec * 1000L },
    )

    val gestureEngine = GestureEngine(application, onGesture = ::triggerSave)

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
    }

    /** Called once by MainActivity after it has bound the CameraX use cases. */
    fun attachVideoCapture(videoCapture: VideoCapture<Recorder>) {
        rollingRecorder.attach(videoCapture)
        rollingRecorder.start(viewModelScope)
    }

    fun updateSettings(update: (AppSettings) -> AppSettings) {
        val next = update(_settings.value)
        _settings.value = next
        applyBufferWindow(next)
        viewModelScope.launch { settingsRepository.save(next) }
    }

    private fun applyBufferWindow(settings: AppSettings) {
        val maxAgeMs = (maxOf(settings.delaySec, settings.backtrackSec) + MARGIN_SEC) * 1000L
        segmentBuffer.setMaxAgeMs(maxAgeMs)
        val ringSize = (maxOf(settings.delaySec, settings.backtrackSec) + MARGIN_SEC) + 1
        rollingRecorder.setRingSize(ringSize)
    }

    private fun onSegmentFinalized(segment: SegmentInfo) {
        segmentBuffer.push(segment)
        if (_appState.value == AppState.LIVE) {
            delayedPlayback.onSegmentFinalized(segment)
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

    /** Manual button and gesture trigger share this one path (spec 3.1). */
    fun triggerSave() {
        if (_appState.value != AppState.LIVE) return
        val currentSettings = settings.value
        val now = System.currentTimeMillis()
        val segments = segmentBuffer.segmentsCoveringRange(now - currentSettings.backtrackSec * 1000L, now)
        if (segments.isEmpty()) {
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
        _galleryClips.value = galleryRepository.listClips()
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
        rollingRecorder.stop()
        gestureEngine.close()
        livePlayer.release()
        replayPlayer.release()
        super.onCleared()
    }
}
