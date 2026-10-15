package com.archery.delaycam.camera

import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy

/** 4:3 source retains 1280x720 after a 16:9 crop, with a bounded CPU/memory cost. */
fun delayImageAnalysis(): ImageAnalysis = ImageAnalysis.Builder()
    .setResolutionSelector(ResolutionSelector.Builder()
        .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
        .setResolutionStrategy(ResolutionStrategy(Size(1280, 960),
            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
        .setResolutionFilter { sizes, _ ->
            // Keep CameraX's ordering and negotiation with Preview. An unusual camera
            // with no size under budget uses its smallest available size.
            sizes.filter { withinCaptureBudget(it.width, it.height) }
                .ifEmpty { listOfNotNull(sizes.minByOrNull { it.width.toLong() * it.height }) }
        }.build())
    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
    .build()

internal fun withinCaptureBudget(width: Int, height: Int): Boolean =
    maxOf(width, height) <= 1280 && minOf(width, height) <= 960

/** Square padding preserves detail in both orientations without restarting the codec.
 * Check codec support before use; candidates only go down, never above budget.
 */
internal fun encoderCanvasCandidates(width: Int, height: Int): List<Int> {
    val desired = ((maxOf(width, height).coerceAtMost(1280) + 15) / 16 * 16).coerceAtLeast(16)
    return (listOf(desired) + listOf(1024, 960, 720, 640, 480, 320, 256, 128).filter { it < desired }).distinct()
}
