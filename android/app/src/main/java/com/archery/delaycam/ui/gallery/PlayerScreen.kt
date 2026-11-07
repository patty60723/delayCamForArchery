package com.archery.delaycam.ui.gallery

import android.content.Intent
import com.archery.delaycam.gallery.ClipPresentationStore
import com.archery.delaycam.gallery.PictureRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.archery.delaycam.ui.DelayCamViewModel
import com.archery.delaycam.ui.components.DeleteClipAction
import com.archery.delaycam.ui.playback.*

@Composable
fun PlayerScreen(viewModel: DelayCamViewModel, clipUri: String, clipName: String, onBack: () -> Unit) {
    val sharing = rememberClipSharing()
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val mirrored by viewModel.mirrorVideo.collectAsState()
    val player = remember(clipUri) { ExoPlayer.Builder(context).build() }
    val ranges by produceState<List<PictureRange>>(emptyList(), clipUri) {
        value = withContext(Dispatchers.IO) { ClipPresentationStore(context).read(clipUri.toUri()) }
    }
    var contentAspect by remember(clipUri) { mutableStateOf<Float?>(null) }
    LaunchedEffect(player, ranges) {
        while (true) {
            contentAspect = ClipPresentationStore.aspectAt(ranges, player.currentPosition)
            delay(100)
        }
    }
    DisposableEffect(player) {
        player.setMediaItem(MediaItem.fromUri(clipUri.toUri()))
        player.playbackParameters = PlaybackParameters(settings.slowMoRate)
        player.prepare()
        player.playWhenReady = true
        onDispose { player.release() }
    }
    MediaReviewLayout(
        video = { modifier -> VideoPlayer(player, mirrored, modifier, contentAspectOverride = contentAspect, pictureMode = settings.pictureMode) },
        header = { Text("已保存影片", Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall) },
        controls = {
            PlaybackControls(player, mirrored, viewModel::setMirrorVideo,
                onSpeedChange = { rate -> viewModel.updateSettings { it.copy(slowMoRate = rate) } })
            OutlinedButton(onClick = { sharing.share(listOf(clipUri.toUri())) }, enabled = !sharing.busy,
                modifier = Modifier.fillMaxWidth()) { Text(if (sharing.busy) "正在準備分享…" else "分享影片") }
            sharing.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            DeleteClipAction(viewModel, clipUri.toUri(), clipName, onDeleted = onBack,
                modifier = Modifier.fillMaxWidth())
        },
        onBack = onBack, backLabel = "返回影片",
    )
}
