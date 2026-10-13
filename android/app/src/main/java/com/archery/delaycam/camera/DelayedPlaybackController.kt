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
 * Baseline playback schedules completed segments after the configured delay.
 * Trial fast return replaces that queue at now-delay and then appends contiguous
 * finalized segments. Leases keep files immutable until all player readers finish.
 * All methods are confined to the player's main thread.
 */
class DelayedPlaybackController(
    private val exoPlayer: ExoPlayer,
    private val getDelaySecMs: () -> Long,
) {
    private val scheduled = mutableSetOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private data class Pinned(val ids: Set<String>, val lease: ImmutableSegmentStore.Lease)
    private val pinned = mutableListOf<Pinned>()
    private var fastTail: SegmentInfo? = null

    fun fastSourcePositionMs(): Long? {
        if (fastTail == null) return null
        val currentId = exoPlayer.currentMediaItem?.mediaId ?: return null
        val source = pinned.asSequence().flatMap { it.lease.segments.asSequence() }
            .firstOrNull { id(it) == currentId } ?: return null
        return source.startedAtMs + exoPlayer.currentPosition.coerceAtMost(source.mediaDurationMs) *
            (source.endedAtMs - source.startedAtMs) / source.mediaDurationMs.coerceAtLeast(1)
    }

    private fun id(segment: SegmentInfo) = "${segment.file.absolutePath}:${segment.startedAtMs}:${segment.endedAtMs}"
    private fun item(segment: SegmentInfo) = MediaItem.Builder()
        .setUri(segment.file.toUri()).setMediaId(id(segment))
        .setMediaMetadata(MediaMetadata.Builder().setExtras(Bundle().apply {
            segment.contentAspectRatio?.let { putFloat(CONTENT_ASPECT_KEY, it) }
        }).build()).build()

    /** Called on main. New playlist owns a lease before any old queue is discarded. */
    fun resume(plan: FastResumePlan, store: ImmutableSegmentStore): Boolean {
        val lease = store.acquire(plan.segments) ?: return false
        reset()
        pinned.add(Pinned(plan.segments.map(::id).toSet(), lease))
        fastTail = plan.segments.last()
        exoPlayer.setMediaItems(plan.segments.map(::item), 0, plan.positionMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        return true
    }

    fun onSegmentFinalized(segment: SegmentInfo, store: ImmutableSegmentStore? = null): Boolean {
        val tail = fastTail
        if (tail != null) {
            if (!FastResumePlan.valid(segment) || segment.startedAtMs < tail.endedAtMs ||
                segment.startedAtMs - tail.endedAtMs > FastResumePlan.MAX_GAP_MS ||
                exoPlayer.mediaItemCount > 40) return false
            val lease = store?.acquire(listOf(segment)) ?: return false
            pinned.add(Pinned(setOf(id(segment)), lease))
            fastTail = segment
            enqueue(segment)
            return true
        }
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
            return true
        }
        if (store != null) {
            val lease = store.acquire(listOf(segment)) ?: run { scheduled.remove(path); return false }
            pinned.add(Pinned(setOf(id(segment)), lease))
        }
        val fireAtMs = segment.endedAtMs + getDelaySecMs()
        val waitMs = (fireAtMs - System.currentTimeMillis()).coerceAtLeast(0)
        handler.postDelayed({
            scheduled.remove(path)
            enqueue(segment)
        }, waitMs)
        return true
    }

    private fun enqueue(segment: SegmentInfo) {
        val firstNewItemIndex = exoPlayer.mediaItemCount
        val wasEnded = exoPlayer.playbackState == Player.STATE_ENDED
        exoPlayer.addMediaItem(item(segment))
        if (exoPlayer.playbackState == Player.STATE_IDLE) {
            exoPlayer.prepare()
        } else if (wasEnded) {
            // Appending after the last item finished does not resume Media3 1.4.1.
            // Seek to the newly added item, preserving the queue and delay.
            exoPlayer.seekToDefaultPosition(firstNewItemIndex)
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
        val activeIds = (0 until exoPlayer.mediaItemCount).map { exoPlayer.getMediaItemAt(it).mediaId }.toSet()
        val iterator = pinned.iterator()
        while (iterator.hasNext()) {
            val pin = iterator.next()
            // Scheduled segments have not entered the player yet.
            if (pin.ids.none { it in activeIds } && pin.lease.segments.none { it.file.absolutePath in scheduled }) {
                pin.lease.close()
                iterator.remove()
            }
        }
    }

    /** Cancel every old timer and reader; the caller decides whether to retain source history. */
    fun reset() {
        scheduled.clear()
        handler.removeCallbacksAndMessages(null)
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        fastTail = null
        pinned.forEach { it.lease.close() }
        pinned.clear()
    }
}

