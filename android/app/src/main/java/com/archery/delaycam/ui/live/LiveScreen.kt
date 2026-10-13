package com.archery.delaycam.ui.live

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.archery.delaycam.settings.PreviewCorner
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
    onPreviewBounds: (Rect?) -> Unit,
) {
    val cameraAspect by viewModel.cameraAspectRatio.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val warmup by viewModel.warmupRemainingSec.collectAsState()
    val state by viewModel.appState.collectAsState()
    val save by viewModel.clipSave.collectAsState()
    val mirrored by viewModel.mirrorVideo.collectAsState()
    var showPreview by rememberSaveable { mutableStateOf(true) }
    var showHelp by remember { mutableStateOf(false) }
    DisposableEffect(onPreviewBounds) { onDispose { onPreviewBounds(null) } }
    val canSave = state == AppState.LIVE && warmup == null &&
        save.phase != SavePhase.SAVING && !(save.phase == SavePhase.FAILED && save.retryable)

    LivePracticeLayout(
        header = { sidePanel ->
            val status = "延遲設定 " + settings.delaySec + " 秒 · " +
                if (warmup == null) "已就緒" else "準備中"
            if (sidePanel) {
                Text(status, Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    TextButton(onClick = onOpenGallery, modifier = Modifier.weight(1f)) { Text("影片") }
                    TextButton(onClick = onOpenSettings, modifier = Modifier.weight(1f)) { Text("設定") }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(status, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = onOpenGallery) { Text("影片") }
                    TextButton(onClick = onOpenSettings) { Text("設定") }
                }
            }
            if (!sidePanel) SaveStatusNotice(save, viewModel::retryClipSave, onOpenSavedClip)
        },
        video = { videoModifier -> BoxWithConstraints(videoModifier) {
            VideoPlayer(viewModel.livePlayer, mirrored, Modifier.fillMaxSize(), fitActivePicture = true)
            GuideOverlay(settings.guideVertical, settings.guideHorizontal, Modifier.fillMaxSize())
            warmup?.let { remaining ->
                DelayWarmupIndicator(remaining, settings.delaySec, Modifier.align(Alignment.Center))
            }
            val landscape = maxWidth > maxHeight
            val widthLimit = if (landscape) (maxWidth * 0.27f).coerceAtMost(240.dp)
                else (maxWidth * 0.42f).coerceAtMost(180.dp)
            val heightLimit = (maxHeight * (if (landscape) 0.42f else 0.48f) - 24.dp).coerceAtLeast(1.dp)
            val previewWidth = minOf(widthLimit, heightLimit * cameraAspect)
            val previewAlignment = if (settings.previewCorner == PreviewCorner.BOTTOM_LEFT)
                Alignment.BottomStart else Alignment.BottomEnd
            if (showPreview) Column(Modifier.align(previewAlignment).padding(8.dp)
                .width(previewWidth).background(MaterialTheme.colorScheme.surface)) {
                Text("即時", Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium)
                Box(Modifier.fillMaxWidth().aspectRatio(cameraAspect)
                    .onGloballyPositioned { onPreviewBounds(it.boundsInRoot()) })
            }
            LaunchedEffect(showPreview) { if (!showPreview) onPreviewBounds(null) }
        } },
        controls = { sidePanel ->
            LiveSaveActions(canSave, sidePanel,
                onSaveAndReview = { viewModel.triggerSave() },
                onSaveOnly = { viewModel.triggerSave(playAfterSave = false) })
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = {
                    showPreview = !showPreview
                }) {
                    Text(if (sidePanel) { if (showPreview) "收起即時" else "顯示即時" }
                        else { if (showPreview) "收起即時視窗" else "顯示即時視窗" })
                }
                TextButton(onClick = { showHelp = true }) { Text("操作說明") }
            }
        },
        feedbackOnRight = showPreview && settings.previewCorner == PreviewCorner.BOTTOM_LEFT,
        landscapeFeedback = { modifier ->
            if (save.phase != SavePhase.IDLE) Surface(modifier,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                shape = MaterialTheme.shapes.medium) {
                SaveStatusNotice(save, viewModel::retryClipSave, onOpenSavedClip)
            }
        },
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
    )
    if (showHelp) AlertDialog(
        onDismissRequest = { showHelp = false },
        title = { Text("練習操作") },
        text = { Text("張開手掌：存檔並回看。\n比讚：開始或停止整段錄影。\n每次比完放下手，才能再次觸發。\n操作前先放下弓。\n\n「只保存」會留在延遲畫面，讓你繼續練習。") },
        confirmButton = { TextButton(onClick = { showHelp = false }) { Text("知道了") } },
    )
}
