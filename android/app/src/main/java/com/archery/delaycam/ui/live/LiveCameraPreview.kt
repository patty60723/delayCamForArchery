package com.archery.delaycam.ui.live

import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Hiding the PiP must keep CameraX's bound Preview Surface attached. */
@Composable
fun LiveCameraPreview(
    preview: Preview,
    visible: Boolean,
    width: Dp,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    aspectRatio: Float = 3f / 4f,
    surfaceRouter: com.archery.delaycam.camera.PreviewSurfaceRouter? = null,
) {
    var host by remember { mutableStateOf<PreviewView?>(null) }
    DisposableEffect(preview, host, surfaceRouter) {
        val provider = host?.surfaceProvider
        provider?.let { if (surfaceRouter != null) surfaceRouter.attach(preview, it) else preview.surfaceProvider = it }
        onDispose { if (provider != null && surfaceRouter != null) surfaceRouter.detach(preview, provider) else if (surfaceRouter == null) preview.setSurfaceProvider(null) }
    }
    Column(modifier.width(if (visible) width else 1.dp)
        .graphicsLayer { alpha = if (visible) 1f else 0f }
        .then(if (visible) Modifier else Modifier.clearAndSetSemantics {})
        .background(MaterialTheme.colorScheme.surface)) {
        if (visible && showLabel) Text("即時", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium)
        AndroidView(factory = { context -> PreviewView(context).apply {
            tag = "live-camera-preview-host"
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            host = this
        } },
            modifier = Modifier.fillMaxWidth().aspectRatio(aspectRatio)
                .semantics { contentDescription = "即時相機取景" })
    }
}
