package com.archery.delaycam.camera

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

private const val TAG = "DelayedPlaybackController"

/**
 * Drives the "always N seconds behind" delay mirror (spec 2.1) by feeding an
 * ExoPlayer a playlist of rolling-buffer segments, each one added exactly
 * `delaySec` after it finished recording. Because ExoPlayer just plays its
 * queue back-to-back in real time, the player naturally stays `delaySec`
 * behind the camera with no manual seeking - this is a direct reading of the
 * spec 5.2 line "延遲顯示透過播放「N 秒前的片段」達成".
 *
 * NOT YET VERIFIED ON DEVICE. Before trusting this: check that consecutive
 * MediaItems from different segment files play back seamlessly (ExoPlayer
 * should pre-buffer the next item, but a visible stutter at each ~1s segment
 * boundary is possible and may need ExoPlayer's `setPauseAtEndOfMediaItems`/
 * preload tuning) and that a mid-session delaySec change doesn't leave stale
 * timers firing against the old delay window.
 */
class DelayedPlaybackController(
    private val exoPlayer: ExoPlayer,
    private val getDelaySecMs: () -> Long,
) {
    private val scheduled = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())

    fun onSegmentFinalized(segment: SegmentInfo) {
        val path = segment.file.absolutePath
        if (!scheduled.add(path)) {
            // Diagnostic for the still-remaining periodic stalls: this
            // path is one of the ring buffer's reused filenames - if this
            // ever fires, an earlier scheduled entry for the same slot
            // hadn't fired yet when it wrapped back around, meaning this
            // segment gets silently dropped (never reaches the player) and
            // the ring buffer is about to overwrite the file the dropped
            // entry still pointed at. Concrete, checkable cause if seen.
            Log.w(TAG, "segment $path already scheduled - dropping this one (ring buffer wrapped before the earlier entry fired)")
            return
        }
        val fireAtMs = segment.endedAtMs + getDelaySecMs()
        val waitMs = (fireAtMs - System.currentTimeMillis()).coerceAtLeast(0)
        handler.postDelayed({
            scheduled.remove(path)
            enqueue(segment)
        }, waitMs)
    }

    private fun enqueue(segment: SegmentInfo) {
        exoPlayer.addMediaItem(MediaItem.fromUri(segment.file.toUri()))
        if (exoPlayer.playbackState == Player.STATE_IDLE) {
            exoPlayer.prepare()
        }
        exoPlayer.playWhenReady = true
        trimPlayedItems()
    }

    /** Drop already-played items so the playlist doesn't grow unbounded over a long session. */
    private fun trimPlayedItems() {
        val current = exoPlayer.currentMediaItemIndex
        // Diagnostic for the still-remaining periodic stalls: if `current`
        // is climbing well past where it normally sits, playback is
        // falling behind additions rather than keeping pace - a symptom,
        // not a cause, but useful to see alongside the per-segment log.
        if (current > 8) {
            Log.w(TAG, "currentMediaItemIndex=$current - playback may be falling behind")
        }
        if (current > 4) {
            exoPlayer.removeMediaItems(0, current - 2)
        }
    }

    /**
     * Called when leaving REPLAY back to LIVE: the backlog scheduled during
     * the replay is stale (it covers a window the archer already watched),
     * so drop it and re-warm-up rather than dumping a burst of old segments
     * into the player - mirrors the web prototype's buffer-clear-on-resume choice.
     */
    fun reset() {
        scheduled.clear()
        handler.removeCallbacksAndMessages(null)
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
    }
}
