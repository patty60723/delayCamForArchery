package com.archery.delaycam.camera

/** Normalizes each input's first PTS and joins samples without absolute-time gaps. */
class SegmentTimeline {
    private var nextStartUs = 0L
    private var firstInputUs: Long? = null
    private var lastOutputUs = -1L
    private var frameDurationUs = 33_333L

    fun beginSegment() { firstInputUs = null }

    fun map(inputUs: Long): Long {
        val first = firstInputUs ?: inputUs.also { firstInputUs = it }
        val output = (nextStartUs + (inputUs - first).coerceAtLeast(0L))
            .coerceAtLeast(lastOutputUs + 1L)
        if (first != inputUs) {
            val delta = output - lastOutputUs
            if (delta in 1_000L..1_000_000L) frameDurationUs = delta
        }
        lastOutputUs = output
        return output
    }

    fun endSegment() {
        if (firstInputUs != null) nextStartUs = lastOutputUs + frameDurationUs
    }

    val durationUs: Long get() = nextStartUs
}
