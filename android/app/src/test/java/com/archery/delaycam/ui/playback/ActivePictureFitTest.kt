package com.archery.delaycam.ui.playback

import org.junit.Assert.*
import org.junit.Test
import com.archery.delaycam.settings.PictureMode

class ActivePictureFitTest {
    @Test fun fillCoversBothAxesWithoutDistortingThePicture() {
        for (width in listOf(300f, 1200f)) for (height in listOf(220f, 800f)) {
            for (encoded in listOf(.75f, 4f / 3f)) for (content in listOf(null, .75f, 4f / 3f)) {
                val scale = activePictureScale(width, height, encoded, content, PictureMode.FILL)
                val aspect = content ?: encoded
                val fw = minOf(width, height * encoded)
                val pw = fw * minOf(1f, aspect / encoded) * scale
                val ph = fw / encoded * minOf(1f, encoded / aspect) * scale
                assertTrue(pw >= width - .01f && ph >= height - .01f)
                assertTrue(kotlin.math.abs(pw - width) < .01f || kotlin.math.abs(ph - height) < .01f)
                assertEquals(aspect, pw / ph, .001f)
            }
        }
    }
    @Test fun portraitEncodedLandscapePictureUsesFullAvailableHeight() {
        // The user's screenshot: 4:3 footage letterboxed inside a 3:4 encoded frame.
        val scale = activePictureScale(1200f, 500f, 0.75f, 4f / 3f)
        assertEquals(16f / 9f, scale, 0.001f)
        assertEquals(500f, 500f * 0.75f / (4f / 3f) * scale, 0.01f)
    }
    @Test fun pictureNeverOverflowsEitherAxisAcrossOrientations() {
        for (width in listOf(300f, 1200f)) for (height in listOf(220f, 800f)) {
            for (encoded in listOf(0.75f, 4f / 3f)) for (content in listOf(0.75f, 4f / 3f)) {
                val scale = activePictureScale(width, height, encoded, content)
                val fittedWidth = minOf(width, height * encoded)
                val pw = fittedWidth * minOf(1f, content / encoded) * scale
                val ph = fittedWidth / encoded * minOf(1f, encoded / content) * scale
                assertTrue(pw <= width + .01f && ph <= height + .01f)
                assertTrue(kotlin.math.abs(pw - width) < .01f || kotlin.math.abs(ph - height) < .01f)
            }
        }
    }
    @Test fun unknownMixedOrInvalidShapesKeepWholeCanvas() {
        for (shape in listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY))
            assertEquals(1f, activePictureScale(1000f, 500f, .75f, shape), 0f)
    }
}
