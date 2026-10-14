package com.archery.delaycam.ui.playback

import kotlin.math.min
import kotlin.math.max
import com.archery.delaycam.settings.PictureMode

const val CONTENT_ASPECT_KEY = "archery.contentAspect"

/** Enlarge only encoder padding; the whole captured image must remain inside the viewport. */
fun activePictureScale(width: Float, height: Float, encodedAspect: Float, contentAspect: Float?,
    mode: PictureMode = PictureMode.FIT): Float {
    if (width <= 0 || height <= 0 || encodedAspect <= 0 || !encodedAspect.isFinite() ||
        !width.isFinite() || !height.isFinite()) return 1f
    // Mixed/legacy footage has no trustworthy active rectangle: keep the whole canvas.
    val pictureAspect = contentAspect?.takeIf { it > 0 && it.isFinite() } ?: encodedAspect
    val fittedWidth = min(width, height * encodedAspect)
    val fittedHeight = fittedWidth / encodedAspect
    val pictureWidth = fittedWidth * min(1f, pictureAspect / encodedAspect)
    val pictureHeight = fittedHeight * min(1f, encodedAspect / pictureAspect)
    return (if (mode == PictureMode.FILL) max(width / pictureWidth, height / pictureHeight)
        else min(width / pictureWidth, height / pictureHeight)).coerceAtLeast(1f)
}
