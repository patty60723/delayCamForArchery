package com.archery.delaycam.gallery

import android.net.Uri

/** One saved clip as listed in the Gallery screen (spec 3.4). */
data class ClipEntry(
    val uri: Uri,
    val displayName: String,
    val addedAtMs: Long,
    val durationMs: Long,
)
