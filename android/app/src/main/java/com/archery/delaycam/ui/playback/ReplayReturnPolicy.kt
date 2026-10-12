package com.archery.delaycam.ui.playback

/** Only an untouched first viewing returns automatically, after a two-second grace period. */
class ReplayReturnPolicy {
    private var automatic = true
    private var deadline: Long? = null
    fun begin() { automatic = true; deadline = null }
    fun interact() { automatic = false; deadline = null }
    fun suspend() { deadline = null }
    fun ended(nowMs: Long) { if (automatic && deadline == null) deadline = nowMs + 2000L }
    fun remainingSeconds(nowMs: Long): Int? = deadline?.let {
        ((it - nowMs).coerceAtLeast(0L) + 999L).div(1000L).toInt()
    }
    fun shouldReturn(nowMs: Long): Boolean = automatic && deadline?.let { nowMs >= it } == true
}
