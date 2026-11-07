package com.archery.delaycam.camera

import android.graphics.Color
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class ImageProxyConversionTest {
    @Test fun rgbaPreservesExactColoursWithPaddingOffsetAndShortFinalRow() {
        // Row stride 16 for two RGBA pixels; final row has no trailing padding.
        val pixels = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE)
        val data = ByteBuffer.allocateDirect(5 + 16 + 8)
        data.position(5)
        for (i in pixels.indices) {
            val p = pixels[i]
            data.position(5 + (i / 2) * 16 + (i % 2) * 4)
            data.put(Color.red(p).toByte()).put(Color.green(p).toByte())
                .put(Color.blue(p).toByte()).put(255.toByte())
        }
        data.position(5)
        val originalLimit = data.limit()
        val bitmap = rgbaToUprightBitmap(2, 2, 16, 4, data, 0)
        try {
            for (i in pixels.indices) assertEquals(pixels[i], bitmap.getPixel(i % 2, i / 2))
            assertEquals(5, data.position())
            assertEquals(originalLimit, data.limit())
        } finally { bitmap.recycle() }
    }

    @Test fun packedRgbaRotatesClockwiseWithoutJpegLoss() {
        val pixels = intArrayOf(Color.RED, Color.GREEN, Color.BLUE, Color.WHITE, Color.BLACK, Color.YELLOW)
        val buffer = ByteBuffer.allocate(3 * 2 * 4)
        for (p in pixels) buffer.put(Color.red(p).toByte()).put(Color.green(p).toByte())
            .put(Color.blue(p).toByte()).put(255.toByte())
        buffer.rewind()
        val bitmap = rgbaToUprightBitmap(3, 2, 12, 4, buffer, 90)
        try {
            assertEquals(2, bitmap.width); assertEquals(3, bitmap.height)
            assertEquals(Color.WHITE, bitmap.getPixel(0, 0))
            assertEquals(Color.RED, bitmap.getPixel(1, 0))
            assertEquals(Color.YELLOW, bitmap.getPixel(0, 2))
            assertEquals(Color.BLUE, bitmap.getPixel(1, 2))
            assertEquals(0, buffer.position())
        } finally { bitmap.recycle() }
    }

    @Test(expected = IllegalArgumentException::class)
    fun truncatedPlaneIsRejectedBeforeReadingOutsideBuffer() {
        rgbaToUprightBitmap(4, 4, 16, 4, ByteBuffer.allocate(63), 0)
    }
}
