package com.archery.delaycam.camera

/**
 * Ring of recent segment metadata, trimmed by age. The native counterpart of
 * the web prototype's FrameBuffer (frameBuffer.js) - same push/trim/range
 * query shape, just holding segment-file references instead of canvases.
 */
class SegmentBuffer(@Volatile private var maxAgeMs: Long) {

    private val segments = ArrayDeque<SegmentInfo>()

    @Synchronized
    fun push(segment: SegmentInfo) {
        segments.addLast(segment)
        trim()
    }

    @Synchronized
    fun setMaxAgeMs(ms: Long) {
        maxAgeMs = ms
        trim()
    }

    private fun trim() {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        while (segments.isNotEmpty() && segments.first().endedAtMs < cutoff) {
            segments.removeFirst()
        }
    }

    @Synchronized
    fun oldestStartMs(): Long? = segments.firstOrNull()?.startedAtMs

    /** Segments that overlap [fromMs, toMs], in chronological order - used for spec 2.2's 回溯存檔. */
    @Synchronized
    fun segmentsCoveringRange(fromMs: Long, toMs: Long): List<SegmentInfo> =
        segments.filter { it.endedAtMs >= fromMs && it.startedAtMs <= toMs }

    @Synchronized
    fun clear() = segments.clear()
}
