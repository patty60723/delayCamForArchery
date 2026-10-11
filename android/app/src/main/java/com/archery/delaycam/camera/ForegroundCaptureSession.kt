package com.archery.delaycam.camera

/**
 * Coordinates Activity lifecycle calls with the camera analysis executor.
 * Every foreground session has a new identity; old frames, startup tasks,
 * and finalized-segment callbacks cannot activate or populate a new session.
 */
class ForegroundCaptureSession {
    private var sessionId = 0L
    private var foreground = false
    private var readySessionId: Long? = null
    private var closed = false

    @Synchronized
    fun beginForeground(): Long? {
        if (closed || foreground) return null
        foreground = true
        readySessionId = null
        return ++sessionId
    }

    @Synchronized
    fun endForeground(): Boolean {
        if (!foreground) return false
        foreground = false
        readySessionId = null
        return true
    }

    @Synchronized
    fun isForeground(): Boolean = foreground

    @Synchronized
    fun isCurrent(candidate: Long): Boolean = foreground && candidate == sessionId

    @Synchronized
    fun markReady(candidate: Long): Boolean {
        if (!foreground || candidate != sessionId) return false
        readySessionId = candidate
        return true
    }

    @Synchronized
    fun sessionForFrame(): Long? = if (foreground) readySessionId else null

    @Synchronized
    fun close() {
        closed = true
        foreground = false
        readySessionId = null
    }
}
