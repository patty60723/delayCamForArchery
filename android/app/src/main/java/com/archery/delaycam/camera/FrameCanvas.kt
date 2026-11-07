package com.archery.delaycam.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)

/** Keep one encoder size across rotations; fit the entire upright frame without stretching. */
internal fun drawFrameFit(canvas: Canvas, bitmap: Bitmap, pictureAspect: Float = bitmap.width.toFloat() / bitmap.height) {
    val cropWidth = minOf(bitmap.width.toFloat(), bitmap.height * pictureAspect)
    val cropHeight = cropWidth / pictureAspect
    val scale = minOf(canvas.width.toFloat() / cropWidth, canvas.height.toFloat() / cropHeight)
    val width = cropWidth * scale
    val height = cropHeight * scale
    val left = (canvas.width - width) / 2f
    val top = (canvas.height - height) / 2f
    // A previous orientation may have occupied pixels outside the new frame.
    canvas.drawColor(Color.BLACK)
    val save = canvas.save()
    canvas.clipRect(left, top, left + width, top + height)
    val bitmapLeft = left - (bitmap.width - cropWidth) / 2f * scale
    val bitmapTop = top - (bitmap.height - cropHeight) / 2f * scale
    canvas.drawBitmap(bitmap, null, RectF(bitmapLeft, bitmapTop,
        bitmapLeft + bitmap.width * scale, bitmapTop + bitmap.height * scale), framePaint)
    canvas.restoreToCount(save)
}
