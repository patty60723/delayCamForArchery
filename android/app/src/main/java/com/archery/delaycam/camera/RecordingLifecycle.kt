package com.archery.delaycam.camera

enum class RecordingPhase { IDLE, STARTING, RECORDING, STOPPING }

/** Main-thread gate. A stop waits for the boundary containing the user's last frames. */
class RecordingLifecycle {
    var phase: RecordingPhase = RecordingPhase.IDLE
        private set
    private var stopAtMs = Long.MAX_VALUE
    private var stopQueued = false

    fun beginStart(): Boolean {
        if (phase != RecordingPhase.IDLE) return false
        phase = RecordingPhase.STARTING
        stopQueued = false
        stopAtMs = Long.MAX_VALUE
        return true
    }

    fun started() {
        // A background event may already have requested stop while creation was pending.
        if (phase == RecordingPhase.STARTING) phase = RecordingPhase.RECORDING
    }

    fun requestStop(nowMs: Long): Boolean {
        if (phase != RecordingPhase.STARTING && phase != RecordingPhase.RECORDING) return false
        phase = RecordingPhase.STOPPING
        stopAtMs = nowMs
        return true
    }

    fun acceptsSegments(): Boolean = phase != RecordingPhase.IDLE && !stopQueued

    fun reachesStopBoundary(endedAtMs: Long): Boolean =
        phase == RecordingPhase.STOPPING && endedAtMs >= stopAtMs

    fun queueStop(): Boolean {
        if (phase != RecordingPhase.STOPPING || stopQueued) return false
        stopQueued = true
        return true
    }

    fun finished() {
        phase = RecordingPhase.IDLE
        stopQueued = false
        stopAtMs = Long.MAX_VALUE
    }
}
