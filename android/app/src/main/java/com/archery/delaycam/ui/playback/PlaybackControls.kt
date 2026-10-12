package com.archery.delaycam.ui.playback

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.archery.delaycam.ui.components.formatMediaTime
import kotlinx.coroutines.delay

@Composable
fun PlaybackControls(
    player: Player,
    mirrored: Boolean,
    onMirrorChange: (Boolean) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onUserInteraction: () -> Unit = {},
    controller: PlaybackSessionController? = null,
) {
    val interaction by rememberUpdatedState(onUserInteraction)
    val speedChange by rememberUpdatedState(onSpeedChange)
    val localController = remember(player) {
        PlaybackSessionController(player, { interaction() }, { speedChange(it) })
    }
    val playback = controller ?: localController
    var durations by remember(player) { mutableStateOf(emptyList<Long>()) }
    var position by remember(player) { mutableLongStateOf(0) }
    var playing by remember(player) { mutableStateOf(player.playWhenReady) }
    var speed by remember(player) { mutableFloatStateOf(player.playbackParameters.speed) }
    var dragging by remember(player) { mutableStateOf<Float?>(null) }
    var error by remember(player) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                speed = playbackParameters.speed
            }
            override fun onPlayerError(playerError: PlaybackException) { error = true }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) error = false
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player) {
        val window = Timeline.Window()
        while (true) {
            durations = List(player.currentTimeline.windowCount) { index ->
                player.currentTimeline.getWindow(index, window).durationMs.coerceAtLeast(0L)
            }
            position = PlaybackTimeline.position(durations, player.currentMediaItemIndex, player.currentPosition)
            playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
            speed = player.playbackParameters.speed
            delay(200)
        }
    }
    val duration = PlaybackTimeline.duration(durations)
    fun seek(targetMs: Long) {
        onUserInteraction()
        val (index, offset) = PlaybackTimeline.target(durations, targetMs)
        player.seekTo(index, offset)
        position = targetMs.coerceIn(0L, duration)
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (error) {
            Text("影片暫時無法播放，請重試或返回", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { onUserInteraction(); player.prepare() }) { Text("重試播放") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMediaTime(dragging?.toLong() ?: position), style = MaterialTheme.typography.labelMedium)
            Text(formatMediaTime(duration), style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = dragging ?: position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
            valueRange = 0f..duration.toFloat().coerceAtLeast(1f),
            enabled = duration > 0,
            onValueChange = { onUserInteraction(); dragging = it },
            onValueChangeFinished = { dragging?.let { seek(it.toLong()) }; dragging = null },
            modifier = Modifier.semantics { contentDescription = "播放時間軸" },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { seek(position - 5_000) }, enabled = duration > 0,
                modifier = Modifier.weight(1f)) { Text("退 5 秒") }
            Button(onClick = {
                onUserInteraction()
                if (playing) player.pause() else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0L)
                    player.play()
                }
                playing = !playing
            }, enabled = player.mediaItemCount > 0, modifier = Modifier.weight(1.2f).heightIn(min = 48.dp)) {
                Text(if (playing) "暫停" else "播放")
            }
            TextButton(onClick = { seek(position + 5_000) }, enabled = duration > 0,
                modifier = Modifier.weight(1f)) { Text("進 5 秒") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0.25f, 0.5f, 1f).forEach { rate ->
                FilterChip(selected = speed == rate, onClick = {
                    playback.selectSpeed(rate)
                    speed = player.playbackParameters.speed
                }, label = { Text(if (rate == 1f) "1×" else "$rate×") },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { playback.restart() }, enabled = player.mediaItemCount > 0,
                modifier = Modifier.heightIn(min = 48.dp)) { Text("重播") }
            Text("鏡像觀看", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = mirrored, onCheckedChange = {
                onUserInteraction(); onMirrorChange(it)
            }, modifier = Modifier.semantics { contentDescription = "鏡像觀看" })
        }
    }
}
