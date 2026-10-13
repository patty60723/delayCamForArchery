package com.archery.delaycam.ui.playback

import android.view.LayoutInflater
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.media3.common.MediaItem
import androidx.media3.common.VideoSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.archery.delaycam.R

@Composable
fun VideoPlayer(player: Player, mirrored: Boolean, modifier: Modifier = Modifier, fitActivePicture: Boolean = false) {
    var videoSize by remember(player) { mutableStateOf(player.videoSize) }
    var contentAspect by remember(player) { mutableStateOf<Float?>(null) }
    DisposableEffect(player, fitActivePicture) {
        fun updateShape() {
            contentAspect = if (fitActivePicture) player.currentMediaItem?.mediaMetadata?.extras
                ?.getFloat(CONTENT_ASPECT_KEY)?.takeIf { it > 0f } else null
        }
        updateShape()
        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) { videoSize = size }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) { updateShape() }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    BoxWithConstraints(modifier.clipToBounds()) {
        val encodedAspect = if (videoSize.height > 0)
            videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height else 0f
        val scale = activePictureScale(maxWidth.value, maxHeight.value, encodedAspect, contentAspect)
        AndroidView(
            factory = { context ->
                (LayoutInflater.from(context).inflate(R.layout.player_view_texture, null) as PlayerView).apply {
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    useController = false
                    this.player = player
                    tag = "archery-video-player"
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = if (mirrored) -scale else scale
                scaleY = scale
            },
        )
    }
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
