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
import com.archery.delaycam.ui.components.DeleteClipAction
import com.archery.delaycam.ui.playback.*

@Composable
fun ReplayScreen(viewModel: DelayCamViewModel, onOpenSavedClip: (Uri, String) -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val save by viewModel.clipSave.collectAsState()
    val mirrored by viewModel.mirrorVideo.collectAsState()
    val remaining by viewModel.replayReturnRemainingSec.collectAsState()
    var help by remember { mutableStateOf(false) }
    BackHandler { viewModel.skipReplay() }
    MediaReviewLayout(
        video = { modifier -> VideoPlayer(viewModel.replayPlayer, mirrored, modifier, fitActivePicture = true, pictureMode = settings.pictureMode) },
        header = {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("回放", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { viewModel.markReplayInteraction(); help = true }) { Text("手勢說明") }
                }
                ReplayReturnNotice(remaining)
                SaveStatusNotice(save, viewModel::retryClipSave, onOpenSavedClip,
                    trailingAction = {
                        if (save.uri != null) DeleteClipAction(viewModel, save.uri, save.displayName,
                            label = "刪除單箭", onDeleted = viewModel::skipReplay,
                            onInteraction = viewModel::markReplayInteraction, modifier = Modifier.width(96.dp))
                    })
            }
        },
        controls = {
            PlaybackControls(player = viewModel.replayPlayer, mirrored = mirrored,
                onMirrorChange = viewModel::setMirrorVideo,
                onSpeedChange = { rate -> viewModel.updateSettings { it.copy(slowMoRate = rate) } },
                onUserInteraction = viewModel::markReplayInteraction, controller = viewModel.replayPlayback)
        },
        onBack = viewModel::skipReplay, backLabel = "返回練習",
        onControlsOpened = viewModel::markReplayInteraction,
    )
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("回放手勢") },
        text = { Text("拇指朝下：返回練習。\n食指朝上：加快一檔，最高 1×。\nV 手勢：放慢一檔，最低 0.25×。\n五指張開：同一段從頭重播。\n比讚：開始或停止整段錄影。\n\n每次保持約半秒，再放下手，才能接受下一次。回放重播不會重複存檔。") },
        confirmButton = { TextButton(onClick = { help = false }) { Text("知道了") } })
}

@Composable
fun ReplayReturnNotice(remaining: Int?) {
    remaining?.let { Text("${it.coerceAtLeast(0)} 秒後返回練習 · 五指可重播",
        Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        style = MaterialTheme.typography.bodyMedium) }
}
