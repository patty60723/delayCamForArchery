package com.archery.delaycam.camera

import android.os.Bundle
import androidx.media3.common.MediaMetadata
import com.archery.delaycam.ui.playback.CONTENT_ASPECT_KEY
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

private const val TAG = "DelayedPlaybackController"

/**
 * Baseline playback delays the initial start, then preloads completed segments.
 * Trial fast return replaces that queue at now-delay and then appends contiguous
 * finalized segments. Leases keep files immutable until all player readers finish.
 * All methods are confined to the player's main thread.
 */
class DelayedPlaybackController(
    private val exoPlayer: ExoPlayer,
    private val getDelaySecMs: () -> Long,
) {
    private val queuedPaths = mutableSetOf<String>()
    private var waitingForStart = false
    private val handler = Handler(Looper.getMainLooper())
    private data class Pinned(val ids: Set<String>, val lease: ImmutableSegmentStore.Lease)
    private val pinned = mutableListOf<Pinned>()
    private var fastTail: SegmentInfo? = null
    private var fastMaxSegmentMs = FastResumePlan.MAX_SEGMENT_MS

    fun fastSourcePositionMs(): Long? {
        if (fastTail == null) return null
        val currentId = exoPlayer.currentMediaItem?.mediaId ?: return null
        val source = pinned.asSequence().flatMap { it.lease.segments.asSequence() }
            .firstOrNull { id(it) == currentId } ?: return null
        return source.startedAtMs + exoPlayer.currentPosition.coerceAtMost(source.mediaDurationMs) *
            (source.endedAtMs - source.startedAtMs) / source.mediaDurationMs.coerceAtLeast(1)
    }

    private fun id(segment: SegmentInfo) = "${segment.file.absolutePath}:${segment.startedAtMs}:${segment.endedAtMs}"
    private fun item(segment: SegmentInfo) = segment.playbackItem(id(segment))

    /** Called on main. New playlist owns a lease before any old queue is discarded. */
    fun resume(plan: FastResumePlan, store: ImmutableSegmentStore): Boolean {
        val lease = store.acquire(plan.segments) ?: return false
        reset()
        pinned.add(Pinned(plan.segments.map(::id).toSet(), lease))
        fastTail = plan.segments.last()
        fastMaxSegmentMs = plan.maxSegmentMs
        exoPlayer.setMediaItems(plan.segments.map(::item), 0, plan.positionMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        return true
    }

    fun onSegmentFinalized(segment: SegmentInfo, store: ImmutableSegmentStore? = null): Boolean {
        val tail = fastTail
        if (tail != null) {
            if (!FastResumePlan.valid(segment, fastMaxSegmentMs) || segment.startedAtMs < tail.endedAtMs ||
                segment.startedAtMs - tail.endedAtMs > FastResumePlan.MAX_GAP_MS ||
                exoPlayer.mediaItemCount > 40) return false
            val lease = store?.acquire(listOf(segment)) ?: return false
            pinned.add(Pinned(setOf(id(segment)), lease))
            fastTail = segment
            enqueue(segment)
            return true
        }
        val path = segment.file.absolutePath
        if (!queuedPaths.add(path)) {
            Log.w(TAG, "segment $path already queued, ignoring duplicate")
            return true
        }
        if (store != null) {
            val lease = store.acquire(listOf(segment)) ?: run { queuedPaths.remove(path); return false }
            pinned.add(Pinned(setOf(id(segment)), lease))
        }
        val initial = exoPlayer.mediaItemCount == 0
        if (initial) waitingForStart = true
        // Completed files are available now: queue them now so Media3 can preload
        // the next period. Per-file delay timers starved the decoder at boundaries.
        enqueue(segment)
        if (initial) {
            // Retain the existing initial delay, but do not prepare/render an early
            // first frame while waiting. Subsequent files follow the media timeline.
            val waitMs = (segment.endedAtMs + getDelaySecMs() - System.currentTimeMillis()).coerceAtLeast(0)
            handler.postDelayed({
                waitingForStart = false
                if (exoPlayer.mediaItemCount > 0) {
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }
            }, waitMs)
        }
        return true
    }

    private fun enqueue(segment: SegmentInfo) {
        val firstNewItemIndex = exoPlayer.mediaItemCount
        val wasEnded = exoPlayer.playbackState == Player.STATE_ENDED
        exoPlayer.addMediaItem(item(segment))
        if (!waitingForStart) {
            if (exoPlayer.playbackState == Player.STATE_IDLE) {
                exoPlayer.prepare()
            } else if (wasEnded) {
                // A genuine capture gap can still exhaust the available files.
                exoPlayer.seekToDefaultPosition(firstNewItemIndex)
            }
            exoPlayer.playWhenReady = true
        }
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
        val activeIds = (0 until exoPlayer.mediaItemCount).map { exoPlayer.getMediaItemAt(it).mediaId }.toSet()
        val activePaths = (0 until exoPlayer.mediaItemCount).mapNotNull {
            exoPlayer.getMediaItemAt(it).localConfiguration?.uri?.path
        }.toSet()
        queuedPaths.retainAll(activePaths)
        val iterator = pinned.iterator()
        while (iterator.hasNext()) {
            val pin = iterator.next()
            if (pin.ids.none { it in activeIds }) {
                pin.lease.close()
                iterator.remove()
            }
        }
    }

    /** Cancel every old timer and reader; the caller decides whether to retain source history. */
    fun reset() {
        queuedPaths.clear()
        waitingForStart = false
        handler.removeCallbacksAndMessages(null)
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        fastTail = null
        fastMaxSegmentMs = FastResumePlan.MAX_SEGMENT_MS
        pinned.forEach { it.lease.close() }
        pinned.clear()
    }
}

