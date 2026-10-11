package com.archery.delaycam.ui.playback

import android.view.LayoutInflater
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.archery.delaycam.R

@Composable
fun VideoPlayer(player: Player, mirrored: Boolean, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            (LayoutInflater.from(context).inflate(R.layout.player_view_texture, null) as PlayerView).apply {
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                useController = false
                this.player = player
            }
        },
        update = { it.player = player },
        modifier = modifier.scale(scaleX = if (mirrored) -1f else 1f, scaleY = 1f),
    )
}

/** Short/landscape viewports keep the controls reachable in a separate scrollable column. */
@Composable
fun MediaReviewLayout(video: @Composable (Modifier) -> Unit, controls: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val controlsMaxHeight = maxHeight * 0.55f
        if (maxWidth >= 600.dp && maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize()) {
                video(Modifier.weight(1.4f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp)) { controls() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                video(Modifier.weight(1f).fillMaxWidth())
                Column(Modifier.fillMaxWidth().heightIn(max = controlsMaxHeight)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) { controls() }
            }
        }
    }
}
