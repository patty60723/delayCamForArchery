package com.archery.delaycam.ui.playback

/** Converts Media3 playlist-relative positions into a single, seekable clip timeline. */
object PlaybackTimeline {
    fun duration(durations: List<Long>): Long = durations.sumOf { it.coerceAtLeast(0) }

    fun position(durations: List<Long>, index: Int, positionMs: Long): Long =
        durations.take(index.coerceAtLeast(0)).sumOf { it.coerceAtLeast(0) } +
            positionMs.coerceIn(0L, durations.getOrElse(index) { 0L }.coerceAtLeast(0L))

    fun target(durations: List<Long>, positionMs: Long): Pair<Int, Long> {
        if (durations.isEmpty()) return 0 to 0L
        var remaining = positionMs.coerceIn(0L, duration(durations))
        durations.forEachIndexed { index, length ->
            val duration = length.coerceAtLeast(0L)
            if (remaining < duration || index == durations.lastIndex) return index to remaining
            remaining -= duration
        }
        return durations.lastIndex to 0L
    }
}
