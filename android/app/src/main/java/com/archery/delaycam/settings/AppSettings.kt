package com.archery.delaycam.settings

/** Mirrors the settings table in spec section 4 (and the web prototype's state.js). */
data class AppSettings(
    val delaySec: Int = 5,
    val backtrackSec: Int = 8,
    val guideVertical: Boolean = true,
    val guideHorizontal: Boolean = true,
    val slowMoRate: Float = 0.5f,
    val previewCorner: PreviewCorner = PreviewCorner.BOTTOM_RIGHT,
    val dominantHand: DominantHand = DominantHand.RIGHT,
    val pictureMode: PictureMode = PictureMode.FIT,
    val captureAspect: CaptureAspect = CaptureAspect.FOUR_THREE,
) {
    companion object {
        val DELAY_RANGE = 1..30
        val BACKTRACK_RANGE = 3..30
        val SLOW_MO_OPTIONS = listOf(1f, 0.5f, 0.25f)
    }
}

enum class DominantHand { LEFT, RIGHT }


enum class PreviewCorner { BOTTOM_LEFT, BOTTOM_RIGHT }

/** Display only: exported footage always retains the complete captured picture. */
enum class PictureMode { FIT, FILL }

enum class CaptureAspect(val wideWidth: Int, val wideHeight: Int) {
    FOUR_THREE(4, 3), SIXTEEN_NINE(16, 9);

    fun ratioFor(width: Int, height: Int): Float = if (width > height)
        wideWidth.toFloat() / wideHeight else wideHeight.toFloat() / wideWidth
}
