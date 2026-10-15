package com.archery.delaycam.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameCanvasTest {
    @Test fun sixteenNineCropsEdgesRatherThanStretchingOrRetainingOldFrame() {
        val source = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val output = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        try {
            source.eraseColor(Color.GREEN)
            val paint = android.graphics.Paint().apply { color = Color.RED }
            Canvas(source).drawRect(0f, 0f, 400f, 30f, paint)
            Canvas(source).drawRect(0f, 270f, 400f, 300f, paint)
            drawFrameFit(Canvas(output), source, 16f / 9f)
            assertEquals(Color.BLACK, output.getPixel(200, 5))
            assertEquals(Color.GREEN, output.getPixel(200, 40))
            assertEquals(Color.GREEN, output.getPixel(200, 260))
            drawFrameFit(Canvas(output), source, 4f / 3f)
            assertEquals(Color.RED, output.getPixel(200, 5))
        } finally { source.recycle(); output.recycle() }
    }
    @Test fun changingOrientationFitsWholeFrameAndClearsPreviousPixels() {
        val output = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val wide = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val tall = Bitmap.createBitmap(300, 400, Bitmap.Config.ARGB_8888)
        try {
            wide.eraseColor(Color.GREEN)
            tall.eraseColor(Color.RED)
            val source = Canvas(tall)
            val paint = android.graphics.Paint().apply { color = Color.BLUE }
            source.drawRect(0f, 0f, 300f, 20f, paint)
            paint.color = Color.YELLOW
            source.drawRect(0f, 380f, 300f, 400f, paint)
            val target = Canvas(output)
            drawFrameFit(target, wide)
            assertEquals(Color.GREEN, output.getPixel(5, 150))
            drawFrameFit(target, tall)
            assertEquals(Color.BLACK, output.getPixel(5, 150))
            assertEquals(Color.BLACK, output.getPixel(395, 150))
            assertEquals(Color.BLUE, output.getPixel(200, 5))
            assertEquals(Color.RED, output.getPixel(200, 150))
            assertEquals(Color.YELLOW, output.getPixel(200, 295))
            drawFrameFit(target, wide)
            assertEquals(Color.GREEN, output.getPixel(5, 150))
            assertEquals(Color.GREEN, output.getPixel(200, 295))
        } finally { output.recycle(); wide.recycle(); tall.recycle() }
    }
}
