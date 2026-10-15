package com.archery.delaycam.camera

/** Exact common aspect ratios with even dimensions accepted by AVC encoders. */
fun captureSize(width: Int, height: Int, aspect: Float): Pair<Int, Int> {
    val (x, y) = when {
        kotlin.math.abs(aspect - 16f / 9f) < .001f -> 16 to 9
        kotlin.math.abs(aspect - 9f / 16f) < .001f -> 9 to 16
        kotlin.math.abs(aspect - 4f / 3f) < .001f -> 4 to 3
        kotlin.math.abs(aspect - 3f / 4f) < .001f -> 3 to 4
        else -> return (width / 2 * 2).coerceAtLeast(2) to (height / 2 * 2).coerceAtLeast(2)
    }
    val units = (minOf(width / x, height / y) / 2 * 2).coerceAtLeast(2)
    return x * units to y * units
}
