package com.archery.delaycam.ui

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.archery.delaycam.BuildConfig
import org.junit.Assert.*
import org.junit.Test

class BrandIdentityTest {
    @Test fun testAppHasShortDistinctLabelAndAdaptiveIcon() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        assertEquals(if (BuildConfig.FAST_RESUME_TRIAL) "測試9·箭影" else "箭影", manager.getApplicationLabel(context.applicationInfo).toString())
        val icon = manager.getApplicationIcon(context.applicationInfo)
        val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
        icon.setBounds(0, 0, 432, 432)
        icon.draw(Canvas(bitmap))
        try {
            if (BuildConfig.FAST_RESUME_TRIAL) {
                var greenPixels = 0
                var firstGreenY = bitmap.height
                var lastGreenY = 0
                for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.green(pixel) > 130 && Color.green(pixel) > Color.red(pixel) * 1.4 &&
                        Color.green(pixel) > Color.blue(pixel) * 1.2) {
                        greenPixels++
                        firstGreenY = minOf(firstGreenY, y)
                        lastGreenY = maxOf(lastGreenY, y)
                    }
                }
                assertTrue("TEST badge must have a visible green area", greenPixels > 3_000)
                assertTrue("Badge cannot be cropped to a thin line", lastGreenY - firstGreenY > 30)
                assertTrue("Entire badge stays inside the launcher mask", lastGreenY < bitmap.height - 10)
            }
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "arrow-replay-test-icon.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ArcheryUiReview")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { bitmap.recycle() }
    }
}
