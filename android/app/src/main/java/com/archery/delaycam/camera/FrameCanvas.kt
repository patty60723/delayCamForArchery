package com.archery.delaycam.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)

/** Keep one encoder size across rotations; fit the entire upright frame without stretching. */
internal fun drawFrameFit(canvas: Canvas, bitmap: Bitmap) {
    val scale = minOf(canvas.width.toFloat() / bitmap.width, canvas.height.toFloat() / bitmap.height)
    val width = bitmap.width * scale
    val height = bitmap.height * scale
    val left = (canvas.width - width) / 2f
    val top = (canvas.height - height) / 2f
    // A previous orientation may have occupied pixels outside the new frame.
    canvas.drawColor(Color.BLACK)
    canvas.drawBitmap(bitmap, null, RectF(left, top, left + width, top + height), framePaint)
}
