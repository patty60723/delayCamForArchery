package com.archery.delaycam.ui.gallery

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.archery.delaycam.ui.DelayCamViewModel

/** Full-screen clip playback opened from the Gallery, with an optional slow-mo toggle. */
@Composable
fun PlayerScreen(
    viewModel: DelayCamViewModel,
    clipUri: String,
    clipName: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    var slowMo by remember { mutableStateOf(false) }

    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) {
        player.setMediaItem(MediaItem.fromUri(clipUri.toUri()))
        player.prepare()
        player.playWhenReady = true
        onDispose { player.release() }
    }
    LaunchedEffect(slowMo, settings.slowMoRate) {
        player.playbackParameters = PlaybackParameters(if (slowMo) settings.slowMoRate else 1f)
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
            IconButton(onClick = onBack) { Text("‹", color = Color.White) }
            Text(clipName, color = Color.White, maxLines = 1)
        }

        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { this.player = player } },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("慢動作播放", color = Color.White)
            Checkbox(checked = slowMo, onCheckedChange = { slowMo = it })
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, clipUri.toUri())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "分享 / 儲存"))
            }) { Text("下載 / 分享") }

            Button(onClick = {
                viewModel.deleteClip(clipUri.toUri())
                onBack()
            }) { Text("刪除") }
        }
    }
}
