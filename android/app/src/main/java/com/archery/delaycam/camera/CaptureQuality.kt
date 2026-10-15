package com.archery.delaycam.camera

import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy

/** Request the selected source ratio. Never silently accept another ratio. */
fun sourceResolutionSelector(aspect: com.archery.delaycam.settings.CaptureAspect): ResolutionSelector =
    ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy(
            if (aspect == com.archery.delaycam.settings.CaptureAspect.SIXTEEN_NINE)
                androidx.camera.core.AspectRatio.RATIO_16_9 else androidx.camera.core.AspectRatio.RATIO_4_3,
            AspectRatioStrategy.FALLBACK_RULE_NONE))
        .setResolutionStrategy(ResolutionStrategy(Size(1280, if (aspect == com.archery.delaycam.settings.CaptureAspect.SIXTEEN_NINE) 720 else 960),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
        .setResolutionFilter { sizes, _ ->
            val matching = sizes.filter { matchesSourceAspect(it.width, it.height, aspect) }
            matching.filter { withinCaptureBudget(it.width, it.height) }
                .ifEmpty { listOfNotNull(matching.minByOrNull { it.width.toLong() * it.height }) }
        }.build()

fun delayImageAnalysis(aspect: com.archery.delaycam.settings.CaptureAspect = com.archery.delaycam.settings.CaptureAspect.FOUR_THREE): ImageAnalysis =
    ImageAnalysis.Builder().setResolutionSelector(sourceResolutionSelector(aspect))
        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

fun cameraPreview(aspect: com.archery.delaycam.settings.CaptureAspect = com.archery.delaycam.settings.CaptureAspect.FOUR_THREE): androidx.camera.core.Preview =
    androidx.camera.core.Preview.Builder().setResolutionSelector(sourceResolutionSelector(aspect)).build()

internal fun withinCaptureBudget(width: Int, height: Int): Boolean =
    maxOf(width, height) <= 1280 && minOf(width, height) <= 960

/** Square padding preserves detail in both orientations without restarting the codec.
 * Check codec support before use; candidates only go down, never above budget.
 */
internal fun encoderCanvasCandidates(width: Int, height: Int): List<Int> {
    val desired = ((maxOf(width, height).coerceAtMost(1280) + 15) / 16 * 16).coerceAtLeast(16)
    return (listOf(desired) + listOf(1024, 960, 720, 640, 480, 320, 256, 128).filter { it < desired }).distinct()
}
