package com.archery.delaycam.camera

import com.archery.delaycam.settings.CaptureAspect
import java.util.concurrent.CancellationException

/** One bounded negotiation, retaining the user's requested output setting. */
internal fun <T> bindPreferredSource(requested: CaptureAspect, bind: (CaptureAspect) -> T): Pair<CaptureAspect, T> {
    val attempts = if (requested == CaptureAspect.SIXTEEN_NINE)
        listOf(requested, CaptureAspect.FOUR_THREE) else listOf(requested)
    var failure: Exception? = null
    for (aspect in attempts) {
        try { return aspect to bind(aspect) }
        catch (error: Exception) {
            if (error is SecurityException || error is CancellationException) throw error
            failure = error
        }
    }
    throw checkNotNull(failure)
}

internal fun matchesSourceAspect(width: Int, height: Int, aspect: CaptureAspect): Boolean {
    val long = maxOf(width, height).toLong()
    val short = minOf(width, height).toLong()
    return short > 0 && long * aspect.wideHeight == short * aspect.wideWidth
}

data class CameraSourceStatus(
    val requested: CaptureAspect = CaptureAspect.FOUR_THREE,
    val source: CaptureAspect? = null,
    val switching: Boolean = true,
    val failed: Boolean = false,
)
