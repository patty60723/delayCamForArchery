package com.archery.delaycam.ui.live

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/** Vertical/horizontal reference lines overlaid on the delay mirror (spec 2.1 / 3.1 / 4). */
@Composable
fun GuideOverlay(showVertical: Boolean, showHorizontal: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val lineColor = Color.White.copy(alpha = 0.55f)
        if (showVertical) {
            drawLine(
                color = lineColor,
                start = androidx.compose.ui.geometry.Offset(size.width / 2f, 0f),
                end = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height),
                strokeWidth = 2f,
            )
        }
        if (showHorizontal) {
            drawLine(
                color = lineColor,
                start = androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
                end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2f),
                strokeWidth = 2f,
            )
        }
    }
}
