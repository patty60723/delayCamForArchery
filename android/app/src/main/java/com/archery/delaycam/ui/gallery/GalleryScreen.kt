package com.archery.delaycam.ui.gallery

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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

    LaunchedEffect(Unit) { viewModel.refreshGallery() }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Text("‹", color = Color.White) }
            Text("畫廊", color = Color.White)
        }

        if (clips.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("尚未有存檔片段", color = Color(0xFF9AA1AB))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(clips) { clip -> ClipCard(clip = clip, onClick = { onOpenClip(clip) }) }
            }
        }
    }
}

@Composable
private fun ClipCard(clip: ClipEntry, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = clip.uri) {
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, clip.uri)
                retriever.frameAtTime
            } catch (e: Exception) {
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
            .background(Color(0xFF14171C))
            .clickable(onClick = onClick),
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)) {
            thumbnail?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = clip.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(modifier = Modifier.padding(8.dp)) {
            Text(clip.displayName, color = Color.White, maxLines = 1)
            Text(clipSubtitle(clip), color = Color(0xFF9AA1AB))
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

