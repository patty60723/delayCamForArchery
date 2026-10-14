package com.archery.delaycam.ui.playback

import android.view.LayoutInflater
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.archery.delaycam.settings.PictureMode

@Composable
fun VideoPlayer(player: Player, mirrored: Boolean, modifier: Modifier = Modifier, fitActivePicture: Boolean = false,
    contentAspectOverride: Float? = null, pictureMode: PictureMode = PictureMode.FIT) {
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
        val scale = activePictureScale(maxWidth.value, maxHeight.value, encodedAspect, contentAspectOverride ?: contentAspect, pictureMode)
        AndroidView(
            factory = { context ->
                (LayoutInflater.from(context).inflate(R.layout.player_view_texture, null) as PlayerView).apply {
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    useController = false
                    this.player = player
                    tag = "archery-video-player"
                }
            },
            update = { it.player = player; it.setTag(R.id.picture_mode, pictureMode.name) },
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = if (mirrored) -scale else scale
                scaleY = scale
            },
        )
    }
}

/** Full-width landscape viewing with reversible overlay controls; portrait remains stacked. */
@Composable
fun MediaReviewLayout(
    video: @Composable (Modifier) -> Unit,
    controls: @Composable () -> Unit,
    header: @Composable () -> Unit = {},
    onBack: (() -> Unit)? = null,
    backLabel: String = "返回",
    onControlsOpened: () -> Unit = {},
) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("media-review-layout")) {
        val controlsMaxHeight = maxHeight * .55f
        if (maxWidth > maxHeight) {
            androidx.activity.compose.BackHandler(expanded) { expanded = false }
            video(Modifier.fillMaxSize().testTag("review-video"))
            Surface(Modifier.align(Alignment.BottomStart).padding(8.dp)
                .width((maxWidth * .4f).coerceAtMost(320.dp)),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
                contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.medium) { header() }
            val panelWidth = (maxWidth * .42f).coerceIn(220.dp, 300.dp)
            Surface(Modifier.align(Alignment.TopEnd).padding(8.dp)
                .width(if (expanded) panelWidth else if (onBack != null) 200.dp else 88.dp)
                .then(if (expanded) Modifier.fillMaxHeight() else Modifier),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
                contentColor = MaterialTheme.colorScheme.onSurface, shape = MaterialTheme.shapes.medium) {
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(backLabel) }
                        TextButton(onClick = {
                            expanded = !expanded
                            if (expanded) onControlsOpened()
                        }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                            contentDescription = if (expanded) "收起播放操作" else "顯示播放操作"
                        }) { Text(if (expanded) "收起" else "操作") }
                    }
                    if (expanded) Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp)) { controls() }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                header()
                video(Modifier.weight(1f).fillMaxWidth().testTag("review-video"))
                Column(Modifier.fillMaxWidth().heightIn(max = controlsMaxHeight)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    controls()
                    if (onBack != null) TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(backLabel) }
                }
            }
        }
    }
}
