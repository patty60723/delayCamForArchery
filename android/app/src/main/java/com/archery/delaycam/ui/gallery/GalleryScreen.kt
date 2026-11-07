package com.archery.delaycam.ui.gallery

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.archery.delaycam.gallery.ClipEntry
import com.archery.delaycam.ui.DelayCamViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saved-clip list (spec 3.4). Thumbnails are grabbed on demand via MediaMetadataRetriever. */
@Composable
fun GalleryScreen(
    viewModel: DelayCamViewModel,
    onBack: () -> Unit,
    onOpenClip: (ClipEntry) -> Unit,
) {
    val clips by viewModel.galleryClips.collectAsState()
    val selection by viewModel.gallerySelection.collectAsState()
    val deleting by viewModel.batchDeleting.collectAsState()
    val singleDeletion by viewModel.clipDeletion.collectAsState()
    val message by viewModel.galleryMessage.collectAsState()
    val loading by viewModel.galleryLoading.collectAsState()
    val sharing = rememberClipSharing()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    val busy = deleting || singleDeletion.busy || sharing.busy
    val selected = clips.filter { it.uri.toString() in selection.ids }
    val back = { if (selection.active && !busy) viewModel.cancelGallerySelection() else onBack() }
    BackHandler(onBack = back)
    LaunchedEffect(Unit) { viewModel.refreshGallery() }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text(if (selection.active && !busy) "取消" else "返回") }
            Text(if (selection.active) "已選 ${selected.size} 段" else "畫廊", Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium)
            if (!selection.active) TextButton(onClick = viewModel::enterGallerySelection,
                enabled = clips.isNotEmpty() && !busy) { Text("選取") }
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selection.active) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    TriStateCheckbox(state = when {
                        selected.isEmpty() -> ToggleableState.Off
                        selected.size == clips.size -> ToggleableState.On
                        else -> ToggleableState.Indeterminate
                    }, onClick = viewModel::selectAllGalleryClips, enabled = !busy && clips.isNotEmpty(),
                        modifier = Modifier.semantics { contentDescription = "全選影片" })
                    Text("全選")
                }
                TextButton(onClick = { sharing.share(selected.map { it.uri }) }, enabled = selected.isNotEmpty() && !busy) { Text("分享") }
                TextButton(onClick = { confirm = true }, enabled = selected.isNotEmpty() && !busy) { Text("刪除", color = if (!busy && selected.isNotEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            } else Text("長按影片可多選、刪除或分享", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(sharing.error ?: if (sharing.busy) "正在準備分享…" else if (loading) "正在讀取畫廊…" else message.orEmpty(),
                Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                color = if (sharing.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { scope.launch { viewModel.refreshGallery() } }, enabled = !busy && !loading) { Text("重新整理") }
        }
        if (clips.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (loading) "正在讀取…" else "尚未有存檔片段", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyVerticalGrid(
            columns = GridCells.Adaptive(156.dp), modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(clips, key = { it.uri.toString() }) { clip ->
                ClipCard(clip, selection.active, clip.uri.toString() in selection.ids, !busy,
                    onClick = { if (selection.active) viewModel.selectGalleryClip(clip.uri.toString()) else onOpenClip(clip) },
                    onLongClick = { viewModel.selectGalleryClip(clip.uri.toString()) })
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("刪除 ${selected.size} 段影片？") },
        text = { Text("將刪除手機中選取的影片，無法從 App 復原。其他影片會保留。") },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("保留影片") } },
        confirmButton = { TextButton(onClick = { confirm = false; viewModel.deleteSelectedGalleryClips() }, enabled = !busy && selected.isNotEmpty()) { Text("確認刪除") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipCard(clip: ClipEntry, selecting: Boolean, checked: Boolean, enabled: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = clip.uri) {
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, clip.uri)
                // Scaled instead of frameAtTime()'s full source resolution:
                // a raw concatenated export can be several minutes of HD
                // video, and decoding a full-res frame per grid card was a
                // plausible OutOfMemoryError source right after a batch of
                // saves. A grid card only ever needs a small bitmap anyway.
                retriever.getScaledFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 320, 180)
            } catch (e: Throwable) {
                // Throwable, not Exception: also guards against
                // OutOfMemoryError from a corrupt or oversized frame - this
                // card just shows no thumbnail rather than taking the whole
                // app down.
                null
            } finally {
                retriever.release()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface)
            .semantics(mergeDescendants = true) {
                if (selecting) { selected = checked; toggleableState = if (checked) ToggleableState.On else ToggleableState.Off }
            }
            .combinedClickable(enabled = enabled, role = if (selecting) Role.Checkbox else Role.Button,
                onClick = onClick, onLongClickLabel = "選取影片", onLongClick = onLongClick),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            thumbnail?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (selecting) Checkbox(checked = checked, onCheckedChange = null,
                modifier = Modifier.align(Alignment.TopEnd).clearAndSetSemantics { })
        }
        Column(modifier = Modifier.padding(8.dp)) {
            Text(clip.displayName, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(clipSubtitle(clip), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun clipSubtitle(clip: ClipEntry): String {
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(clip.addedAtMs))
    return "$time · ${formatClipDuration(clip.durationMs)}"
}

// Per-shot clips are a few seconds ("4.2s"); continuous-recording sessions
// (see ContinuousRecordingSession.kt) can run many minutes, where that
// format stops being readable, so switch to mm:ss past one minute.
private fun formatClipDuration(ms: Long): String {
    if (ms < 60_000) return "${"%.1f".format(ms / 1000.0)}s"
    val totalSec = ms / 1000
    val mm = (totalSec / 60).toString().padStart(2, '0')
    val ss = (totalSec % 60).toString().padStart(2, '0')
    return "$mm:$ss"
}

