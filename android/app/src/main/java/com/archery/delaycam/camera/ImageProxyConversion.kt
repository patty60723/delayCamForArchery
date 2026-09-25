package com.archery.delaycam.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

/**
 * Shared YUV_420_888 -> upright RGB Bitmap conversion. Originally lived
 * only inside GestureEngine (for MediaPipe input); pulled out here so
 * ContinuousSegmentEncoder can feed the exact same already-proven
 * conversion to its video encoder, instead of the hand-rolled direct
 * YUV-plane-to-MediaCodec buffer copy that used to live there - confirmed
 * on-device that threw a BufferOverflowException on every single frame,
 * so no segment was ever produced. Reusing this one working
 * implementation for both consumers means there's exactly one place this
 * logic can be wrong, not two independently-written copies.
 *
 * The YUV_420_888 -> NV21 conversion assumes pixelStride == 1 on the U/V
 * planes, true for most devices' CameraX ImageAnalysis output but not
 * guaranteed on every device - if colors look wrong (not detection/
 * recording failing outright, actual visible color corruption), that's
 * the first place to check.
 */
fun ImageProxy.toRotatedBitmap(): Bitmap {
    val bitmap = toBitmapViaYuv()
    return rotateIfNeeded(bitmap, imageInfo.rotationDegrees)
}

private fun ImageProxy.toBitmapViaYuv(): Bitmap {
    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer
    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()
    val nv21 = ByteArray(ySize + uSize + vSize)
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 90, out)
    val jpegBytes = out.toByteArray()
    return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
}

private fun rotateIfNeeded(bitmap: Bitmap, degrees: Int): Bitmap {
    if (degrees == 0) return bitmap
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
}
