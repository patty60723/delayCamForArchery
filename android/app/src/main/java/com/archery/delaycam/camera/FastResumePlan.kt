package com.archery.delaycam.camera

import kotlin.math.abs

/** Select current source time, never the playback position paused before replay. */
data class FastResumePlan(val segments: List<SegmentInfo>, val positionMs: Long) {
    companion object {
        const val MAX_GAP_MS = 150L
        const val MAX_SEGMENT_MS = 5_000L

        fun valid(segment: SegmentInfo): Boolean {
            val duration = segment.endedAtMs - segment.startedAtMs
            return duration in 100L..MAX_SEGMENT_MS &&
                abs(duration - segment.mediaDurationMs) <= 500L
        }

        fun create(segments: List<SegmentInfo>, nowMs: Long, delayMs: Long): FastResumePlan? {
            if (delayMs <= 0 || segments.isEmpty()) return null
            val target = nowMs - delayMs
            val first = segments.indexOfFirst { target >= it.startedAtMs && target < it.endedAtMs }
            if (first < 0) return null
            val selected = segments.drop(first)
            if (selected.any { !valid(it) } || selected.map { it.file }.distinct().size != selected.size) return null
            if (selected.zipWithNext().any { (a, b) ->
                    b.startedAtMs < a.endedAtMs || b.startedAtMs - a.endedAtMs > MAX_GAP_MS
                }) return null
            val last = selected.last()
            // A stale camera or a target too close to the unfinished segment needs warmup.
            if (nowMs - last.endedAtMs !in 0L..MAX_SEGMENT_MS || last.endedAtMs - target < 500L) return null
            val source = selected.first()
            val position = ((target - source.startedAtMs) * source.mediaDurationMs /
                (source.endedAtMs - source.startedAtMs)).coerceAtLeast(0)
            return FastResumePlan(selected, position)
        }
    }
}
