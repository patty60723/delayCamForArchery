package com.archery.delaycam.camera

import java.util.TreeMap

/** Canvas Surface timestamps and MediaCodec output PTS share the monotonic nanosecond clock. */
internal class FrameShapeTimeline {
    private val changes = TreeMap<Long, Float>()

    @Synchronized fun submitted(timeUs: Long, aspect: Float) {
        if (changes.lastEntry()?.value != aspect) changes[timeUs] = aspect
    }

    @Synchronized fun at(presentationTimeUs: Long): Float? {
        val entry = changes.floorEntry(presentationTimeUs) ?: return null
        // Keep the active shape and any not-yet-encoded changes, not the full session history.
        changes.headMap(entry.key, false).clear()
        return entry.value
    }
}
