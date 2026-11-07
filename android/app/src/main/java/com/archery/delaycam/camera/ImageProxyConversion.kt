package com.archery.delaycam.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.PixelFormat
import androidx.camera.core.ImageProxy
import java.nio.ByteBuffer

/** CameraX performs native YUV -> RGBA conversion; no JPEG encode/decode in this path. */
fun ImageProxy.toRotatedBitmap(): Bitmap {
    require(format == PixelFormat.RGBA_8888) { "Expected CameraX RGBA, got $format" }
    val plane = planes[0]
    return rgbaToUprightBitmap(width, height, plane.rowStride, plane.pixelStride,
        plane.buffer, imageInfo.rotationDegrees)
}

/** Respect row padding, buffer offset and the last row's potentially missing padding. */
internal fun rgbaToUprightBitmap(width: Int, height: Int, rowStride: Int, pixelStride: Int,
    buffer: ByteBuffer, rotationDegrees: Int): Bitmap {
    require(width > 0 && height > 0 && pixelStride == 4 && rowStride >= width * 4)
    val source = buffer.duplicate()
    val offset = source.position()
    require(source.remaining().toLong() >= (height - 1L) * rowStride + width * 4L)
    val pixels = if (rowStride == width * 4) source else {
        ByteBuffer.allocate(width * height * 4).apply {
            for (row in 0 until height) {
                source.limit(offset + row * rowStride + width * 4)
                source.position(offset + row * rowStride)
                put(source)
            }
            rewind()
        }
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        bitmap.copyPixelsFromBuffer(pixels)
        if (rotationDegrees % 360 == 0) return bitmap
        val upright = Bitmap.createBitmap(bitmap, 0, 0, width, height,
            Matrix().apply { postRotate(rotationDegrees.toFloat()) }, false)
        if (upright !== bitmap) bitmap.recycle()
        return upright
    } catch (failure: Throwable) { bitmap.recycle(); throw failure }
}
