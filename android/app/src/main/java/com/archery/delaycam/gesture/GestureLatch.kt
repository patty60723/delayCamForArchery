package com.archery.delaycam.gesture

/** One command per held pose; cooldown alone does not rearm a gesture. */
class GestureLatch(private val stableMs: Long, private val cooldownMs: Long) {
    private var streakStart: Long? = null
    private var lastFireAt: Long? = null
    private var firedWhileHeld = false

    @Synchronized fun evaluate(matched: Boolean, nowMs: Long): Boolean {
        if (!matched) {
            streakStart = null
            firedWhileHeld = false
            return false
        }
        if (firedWhileHeld) return false
        val start = streakStart ?: nowMs.also { streakStart = it }
        val last = lastFireAt
        if (nowMs - start < stableMs || (last != null && nowMs - last < cooldownMs)) return false
        firedWhileHeld = true
        lastFireAt = nowMs
        return true
    }

    @Synchronized fun suspendUntilRelease() {
        streakStart = null
        firedWhileHeld = true
    }
}
