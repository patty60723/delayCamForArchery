package com.archery.delaycam.camera

import java.io.File

/**
 * Metadata for one rolling-buffer segment file (spec 5.2: short segment
 * files cycling in the app's private cache dir, not shown to the user).
 * Timestamps are wall-clock (System.currentTimeMillis()) so the delayed
 * playback controller and the backtrack export can reason about "N seconds
 * ago" directly against real time.
 */
data class SegmentInfo(
    val file: File,
    val startedAtMs: Long,
    val endedAtMs: Long,
)
