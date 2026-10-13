package com.archery.delaycam.ui.playback

import kotlin.math.min

const val CONTENT_ASPECT_KEY = "archery.contentAspect"

/** Enlarge only encoder padding; the whole captured image must remain inside the viewport. */
fun activePictureScale(width: Float, height: Float, encodedAspect: Float, contentAspect: Float?): Float {
    if (width <= 0 || height <= 0 || encodedAspect <= 0 || !encodedAspect.isFinite() ||
        contentAspect == null || contentAspect <= 0 || !contentAspect.isFinite()) return 1f
    val fittedWidth = min(width, height * encodedAspect)
    val fittedHeight = fittedWidth / encodedAspect
    val pictureWidth = fittedWidth * min(1f, contentAspect / encodedAspect)
    val pictureHeight = fittedHeight * min(1f, encodedAspect / contentAspect)
    return min(width / pictureWidth, height / pictureHeight).coerceAtLeast(1f)
}
