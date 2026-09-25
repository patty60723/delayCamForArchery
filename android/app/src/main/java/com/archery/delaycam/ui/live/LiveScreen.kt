package com.archery.delaycam.ui.live

import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.archery.delaycam.settings.DominantHand
import com.archery.delaycam.state.AppState
import com.archery.delaycam.ui.DelayCamViewModel

/**
 * Main screen (spec 3.1). CameraX itself is bound once at the Activity level
 * (see MainActivity) so the rolling buffer / gesture detection keep running
 * while the user is on Settings or Gallery - this screen only owns the
 * PreviewView surface for the small live PiP window, handed off to
 * viewModel.previewUseCase whenever it's visible.
 */
@Composable
fun LiveScreen(
    viewModel: DelayCamViewModel,
    onOpenSettings: () -> Unit,
    onOpenGallery: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val warmupRemaining by viewModel.warmupRemainingSec.collectAsState()
    val gestureStatus by viewModel.gestureStatus.collectAsState()
    val appState by viewModel.appState.collectAsState()
    val isRecording by viewModel.isContinuousRecording.collectAsState()
    val recordingElapsedSec by viewModel.recordingElapsedSec.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {

        // Delayed mirror (spec 2.1): the ExoPlayer fed by DelayedPlaybackController.
        AndroidView(
            // Mirrored (scaleX = -1) so it reads like an actual mirror, not
            // "how someone facing you sees you" - the whole point of the
            // delay mirror (spec 2.1). Front-camera capture is NOT mirrored
            // at the sensor/recording level (matches every camera app's
            // saved-video convention), so this is purely a display-time flip;
            // the underlying segment/clip files stay unflipped.
            modifier = Modifier.fillMaxSize().scale(scaleX = -1f, scaleY = 1f),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    player = viewModel.livePlayer
                }
            },
        )

        GuideOverlay(
            showVertical = settings.guideVertical,
            showHorizontal = settings.guideHorizontal,
            modifier = Modifier.fillMaxSize(),
        )

        if (warmupRemaining != null && appState == AppState.LIVE) {
            WarmupOverlay(remainingSec = warmupRemaining!!)
        }

        // Small always-live (undelayed) preview, opposite corner from the dominant hand
        // so raising that hand to gesture doesn't cover it.
        AndroidView(
            factory = { ctx -> PreviewView(ctx) },
            update = { previewView -> viewModel.previewUseCase.surfaceProvider = previewView.surfaceProvider },
            // Mirrored for the same reason as the main delayed view above -
            // CameraX's PreviewView does not auto-mirror the front camera.
            modifier = Modifier
                .align(if (settings.dominantHand == DominantHand.LEFT) Alignment.TopEnd else Alignment.TopStart)
                .padding(top = 72.dp, start = 12.dp, end = 12.dp)
                .width(110.dp)
                .aspectRatio(3f / 4f)
                .background(Color.Black, RoundedCornerShape(12.dp))
                .scale(scaleX = -1f, scaleY = 1f),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .padding(top = 34.dp, start = 12.dp, end = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(shape = RoundedCornerShape(999.dp), color = Color.Black.copy(alpha = 0.6f)) {
                    Text(
                        text = gestureStatus,
                        color = Color(0xFF9AA1AB),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
                if (isRecording) {
                    Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.error) {
                        Text(
                            text = "⏺ REC ${formatElapsed(recordingElapsedSec)}",
                            color = Color.White,
                            maxLines = 1,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
            Row {
                IconButton(onClick = { viewModel.toggleContinuousRecording() }) {
                    Text(
                        text = if (isRecording) "⏹" else "⏺", // ⏹ / ⏺
                        color = if (isRecording) MaterialTheme.colorScheme.error else Color.White,
                    )
                }
                IconButton(onClick = onOpenGallery) {
                    Text("🖼", color = Color.White) // 🖼 gallery
                }
                IconButton(onClick = onOpenSettings) {
                    Text("⚙", color = Color.White) // ⚙ settings
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 26.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(shape = RoundedCornerShape(10.dp), color = Color.Black.copy(alpha = 0.35f)) {
                Text(
                    text = "跟隨動作後比出「張開手掌」即可自動存檔;比「讚」開始/停止連續錄影,不用每箭都比",
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Button(
                onClick = { viewModel.triggerSave() },
                enabled = appState == AppState.LIVE,
                shape = CircleShape,
                modifier = Modifier.padding(top = 10.dp).size(68.dp),
            ) { Text("存檔") }
        }
    }
}

private fun formatElapsed(totalSec: Int): String {
    val mm = (totalSec / 60).toString().padStart(2, '0')
    val ss = (totalSec % 60).toString().padStart(2, '0')
    return "$mm:$ss"
}

@Composable
private fun WarmupOverlay(remainingSec: Int) {
    Box(
        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(
                text = "準備延遲畫面…$remainingSec 秒",
                color = Color(0xFF9AA1AB),
                modifier = Modifier.padding(top = 14.dp),
            )
        }
    }
}
