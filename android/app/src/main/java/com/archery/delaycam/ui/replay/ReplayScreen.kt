package com.archery.delaycam.ui.replay

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.archery.delaycam.ui.DelayCamViewModel
import com.archery.delaycam.ui.components.SaveStatusNotice
import com.archery.delaycam.ui.playback.*

@Composable
fun ReplayScreen(viewModel: DelayCamViewModel, onOpenSavedClip: (Uri, String) -> Unit) {
    val save by viewModel.clipSave.collectAsState()
    val mirrored by viewModel.mirrorVideo.collectAsState()
    BackHandler { viewModel.skipReplay() }
    Column(Modifier.fillMaxSize()) {
        Text("回放", Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        SaveStatusNotice(save, viewModel::retryClipSave, onOpenSavedClip)
        Box(Modifier.weight(1f)) {
            MediaReviewLayout(
                video = { modifier -> VideoPlayer(viewModel.replayPlayer, mirrored, modifier) },
                controls = {
                    PlaybackControls(
                        player = viewModel.replayPlayer,
                        mirrored = mirrored,
                        onMirrorChange = viewModel::setMirrorVideo,
                        onSpeedChange = { rate -> viewModel.updateSettings { it.copy(slowMoRate = rate) } },
                        onUserInteraction = viewModel::markReplayInteraction,
                    )
                    OutlinedButton(onClick = viewModel::skipReplay,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).heightIn(min = 48.dp)) {
                        Text("返回練習")
                    }
                },
            )
        }
    }
}
