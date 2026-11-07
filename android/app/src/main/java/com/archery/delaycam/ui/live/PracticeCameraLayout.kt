package com.archery.delaycam.ui.live

import androidx.camera.core.Preview
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** One attached Surface host survives Live/Replay navigation and PiP visibility changes. */
@Composable
fun PracticeCameraLayout(
    preview: Preview,
    live: Boolean,
    content: @Composable ((Rect?) -> Unit) -> Unit,
) {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    val report = remember { { rect: Rect? -> bounds = rect } }
    val density = LocalDensity.current.density
    Box(Modifier.fillMaxSize()) {
        content(report)
        val visibleBounds = bounds.takeIf { live }
        LiveCameraPreview(preview, visibleBounds != null,
            width = visibleBounds?.let { (it.width / density).dp } ?: 1.dp,
            modifier = Modifier.offset { visibleBounds?.let {
                IntOffset(it.left.roundToInt(), it.top.roundToInt())
            } ?: IntOffset.Zero }, showLabel = false,
            aspectRatio = visibleBounds?.let { it.width / it.height } ?: 3f / 4f)
    }
}
