package com.archery.delaycam.ui.live

import android.content.res.Configuration
import android.view.LayoutInflater
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.ui.PlayerView
import com.archery.delaycam.R
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
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // The main delayed view below fills the whole screen with RESIZE_MODE_ZOOM,
    // which crops the camera feed to the screen's own aspect ratio. The PiP
    // preview used to hardcode 3f/4f regardless of screen shape, so it framed
    // the same feed differently from the delayed view - matching this to the
    // actual screen ratio makes the two views show the same crop.
    val screenAspectRatio = configuration.screenWidthDp.toFloat() / configuration.screenHeightDp.toFloat()
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
                // Inflated with surface_type="texture_view" (see the layout
                // file's comment): a plain PlayerView defaults to a
                // SurfaceView, which does not render through the scale
                // transform above and was showing nothing at all.
                (LayoutInflater.from(ctx).inflate(R.layout.player_view_texture, null) as PlayerView).apply {
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
            factory = { ctx ->
                PreviewView(ctx).apply {
                    // COMPATIBLE forces a TextureView-backed implementation.
                    // PreviewView's default (PERFORMANCE) prefers a
                    // SurfaceView, which - same as LiveScreen's main
                    // PlayerView above - does not render through the scale
                    // transform below.
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            },
            update = { previewView -> viewModel.previewUseCase.surfaceProvider = previewView.surfaceProvider },
            // NOT manually mirrored, unlike the main delayed view above -
            // this was the actual bug behind "PiP and delayed view are
            // mirror images of each other, not sure which one's wrong":
            // PreviewView.getSurfaceProvider() (used via `update` above)
            // auto-mirrors front-camera content by default - that's
            // PreviewView's own documented behavior, specifically because
            // it's meant to show a live viewfinder the way a user expects
            // (like a mirror), unlike ImageAnalysis/raw camera output,
            // which never mirrors. The old comment here claiming
            // "PreviewView does not auto-mirror" was simply wrong. Adding
            // our own scale(-1) on top of that auto-mirror flipped it back
            // to unmirrored - the delayed view (plain ExoPlayer replaying
            // our own recording, no camera-facing logic of its own) was
            // the one correctly mirrored all along via its own explicit
            // flip; this view just needed its redundant one removed.
            // Corner is always opposite the dominant hand (spec: raising
            // that hand to gesture shouldn't cover it); which corner
            // (top/bottom) depends on orientation - reported that in
            // landscape, sitting near the top put it awkwardly close to
            // vertical-center given how short landscape height is, so
            // landscape uses the bottom corner instead. Portrait keeps the
            // top corner unchanged.
            modifier = Modifier
                .align(
                    when {
                        settings.dominantHand == DominantHand.LEFT && isLandscape -> Alignment.BottomEnd
                        settings.dominantHand == DominantHand.LEFT -> Alignment.TopEnd
                        isLandscape -> Alignment.BottomStart
                        else -> Alignment.TopStart
                    }
                )
                .padding(
                    top = if (isLandscape) 0.dp else 72.dp,
                    bottom = if (isLandscape) 12.dp else 0.dp,
                    start = 12.dp,
                    end = 12.dp,
                )
                .width(110.dp)
                .aspectRatio(screenAspectRatio)
                .background(Color.Black, RoundedCornerShape(12.dp)),
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
                    text = "張開手掌自動播放前${settings.backtrackSec}秒慢動作\n比讚開始或停止錄影",
                    color = Color.White,
                    textAlign = TextAlign.Center,
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
