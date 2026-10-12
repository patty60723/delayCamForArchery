package com.archery.delaycam.ui.playback

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player

/** Touch and gesture commands share this player and one interaction/persistence policy. */
class PlaybackSessionController(
    private val player: Player,
    private val onInteraction: () -> Unit = {},
    private val onSpeedChange: (Float) -> Unit = {},
    private val onFeedback: (String) -> Unit = {},
) {
    private val rates = listOf(0.25f, 0.5f, 1f)
    private fun ready(): Boolean {
        if (player.mediaItemCount > 0) return true
        onFeedback("影片還在準備中")
        return false
    }
    fun restart(): Boolean {
        if (!ready()) return false
        onInteraction()
        player.seekTo(0, 0L)
        player.play()
        onFeedback("已重播")
        return true
    }
    fun selectSpeed(rate: Float) {
        if (rate !in rates || !ready()) return
        onInteraction()
        player.playbackParameters = PlaybackParameters(rate)
        onSpeedChange(rate)
        onFeedback("回放 ${rateText(rate)}")
    }
    fun changeSpeed(faster: Boolean) {
        if (!ready()) return
        val speed = player.playbackParameters.speed
        val next = if (faster) rates.firstOrNull { it > speed } else rates.lastOrNull { it < speed }
        if (next == null) {
            onInteraction()
            onFeedback(if (faster) "已是最快 1×" else "已是最慢 0.25×")
        } else selectSpeed(next)
    }
    companion object {
        fun rateText(rate: Float): String = if (rate == 1f) "1×" else "$rate×"
    }
}
