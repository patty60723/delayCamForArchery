package com.archery.delaycam.ui.replay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.archery.delaycam.ui.DelayCamViewModel
import kotlinx.coroutines.delay

/** Slow-motion replay of the just-saved clip (spec 3.2). Auto-returns to Live when playback ends. */
@Composable
fun ReplayScreen(viewModel: DelayCamViewModel) {
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            val duration = viewModel.replayPlayer.duration
            progress = if (duration > 0) {
                (viewModel.replayPlayer.currentPosition.toFloat() / duration).coerceIn(0f, 1f)
            } else 0f
            delay(100)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            // Mirrored - see LiveScreen.kt's main PlayerView for why. Keeps
            // the "still looking in the mirror" continuity right after the
            // shot; the Gallery's player deliberately does NOT mirror (see
            // PlayerScreen.kt) since it has visible transport controls that
            // a horizontal flip would render backwards/unreadable.
            modifier = Modifier.fillMaxSize().scale(scaleX = -1f, scaleY = 1f),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    player = viewModel.replayPlayer
                }
            },
        )

        Surface(
            shape = RoundedCornerShape(999.dp),
            color = Color(0xFFFF7A1A).copy(alpha = 0.15f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 34.dp),
        ) {
            Text("慢動作回放", color = Color(0xFFFF7A1A), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 74.dp, start = 16.dp, end = 16.dp)
                .fillMaxWidth()
                .height(4.dp),
            color = Color(0xFFFF7A1A),
        )

        Button(
            onClick = { viewModel.skipReplay() },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp),
        ) { Text("立即返回") }
    }
}
