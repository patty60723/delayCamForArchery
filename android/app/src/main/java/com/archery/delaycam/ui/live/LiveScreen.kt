package com.archery.delaycam.ui.live

import android.net.Uri
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.archery.delaycam.settings.DominantHand
import com.archery.delaycam.state.AppState
import com.archery.delaycam.state.SavePhase
import com.archery.delaycam.ui.DelayCamViewModel
import com.archery.delaycam.ui.components.SaveStatusNotice
import com.archery.delaycam.ui.playback.VideoPlayer

@Composable
fun LiveScreen(
    viewModel: DelayCamViewModel,
    onOpenSettings: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenSavedClip: (Uri, String) -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val warmup by viewModel.warmupRemainingSec.collectAsState()
    val state by viewModel.appState.collectAsState()
    val save by viewModel.clipSave.collectAsState()
    val mirrored by viewModel.mirrorVideo.collectAsState()
    var showPreview by remember { mutableStateOf(true) }
    var showHelp by remember { mutableStateOf(false) }
    val canSave = state == AppState.LIVE && warmup == null &&
        save.phase != SavePhase.SAVING && !(save.phase == SavePhase.FAILED && save.retryable)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("延遲設定 " + settings.delaySec + " 秒 · " +
                if (warmup == null) "已就緒" else "準備中",
                Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = onOpenGallery) { Text("影片") }
            TextButton(onClick = onOpenSettings) { Text("設定") }
        }
        SaveStatusNotice(save, viewModel::retryClipSave, onOpenSavedClip)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            VideoPlayer(viewModel.livePlayer, mirrored, Modifier.fillMaxSize())
            GuideOverlay(settings.guideVertical, settings.guideHorizontal, Modifier.fillMaxSize())
            if (warmup != null) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(if (warmup == 0) "等待延遲影片就緒…" else "準備延遲畫面…" + warmup + " 秒",
                        Modifier.padding(top = 12.dp))
                }
            }
            if (showPreview) {
                Column(Modifier.align(if (settings.dominantHand == DominantHand.LEFT)
                    Alignment.TopEnd else Alignment.TopStart).padding(8.dp).width(96.dp)
                    .background(MaterialTheme.colorScheme.surface)) {
                    Text("即時", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium)
                    AndroidView(factory = { context -> PreviewView(context).apply {
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        scaleType = PreviewView.ScaleType.FIT_CENTER
                    } }, update = { viewModel.previewUseCase.surfaceProvider = it.surfaceProvider },
                        modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f)
                            .semantics { contentDescription = "即時相機取景" })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.triggerSave() }, enabled = canSave,
                modifier = Modifier.weight(1.2f).heightIn(min = 56.dp)) { Text("存檔並回看") }
            OutlinedButton(onClick = { viewModel.triggerSave(playAfterSave = false) }, enabled = canSave,
                modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("只保存") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { showPreview = !showPreview }) {
                Text(if (showPreview) "收起即時視窗" else "顯示即時視窗")
            }
            TextButton(onClick = { showHelp = true }) { Text("操作說明") }
        }
    }
    if (showHelp) AlertDialog(
        onDismissRequest = { showHelp = false },
        title = { Text("練習操作") },
        text = { Text("張開手掌：存檔並回看。\n比讚：開始或停止整段錄影。\n每次比完放下手，才能再次觸發。\n操作前先放下弓。\n\n「只保存」會留在延遲畫面，讓你繼續練習。") },
        confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } },
    )
}
