package com.archery.delaycam.ui.gallery

import android.content.Intent
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
import com.archery.delaycam.ui.playback.*

@Composable
fun PlayerScreen(viewModel: DelayCamViewModel, clipUri: String, clipName: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val mirrored by viewModel.mirrorVideo.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    val player = remember(clipUri) { ExoPlayer.Builder(context).build() }
    DisposableEffect(player) {
        player.setMediaItem(MediaItem.fromUri(clipUri.toUri()))
        player.playbackParameters = PlaybackParameters(settings.slowMoRate)
        player.prepare()
        player.playWhenReady = true
        onDispose { player.release() }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            TextButton(onClick = onBack) { Text("返回影片") }
            Text("已保存影片", Modifier.padding(12.dp), style = MaterialTheme.typography.titleSmall)
        }
        Box(Modifier.weight(1f)) {
            MediaReviewLayout(
                video = { modifier -> VideoPlayer(player, mirrored, modifier) },
                controls = {
                    PlaybackControls(player, mirrored, viewModel::setMirrorVideo,
                        onSpeedChange = { rate -> viewModel.updateSettings { it.copy(slowMoRate = rate) } })
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "video/mp4"
                                putExtra(Intent.EXTRA_STREAM, clipUri.toUri())
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "分享影片"))
                        }, modifier = Modifier.weight(1f)) { Text("分享影片") }
                        TextButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f)) {
                            Text("刪除影片", color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        }
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("刪除這段影片？") },
        text = { Text(clipName + "\n刪除後無法從 App 復原。") },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("保留影片") } },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            viewModel.deleteClip(clipUri.toUri(), onDeleted = onBack)
        }) { Text("刪除影片", color = MaterialTheme.colorScheme.error) } },
    )
}
