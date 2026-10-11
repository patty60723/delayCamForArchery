package com.archery.delaycam.state

import android.net.Uri

enum class SavePhase { IDLE, SAVING, SAVED, FAILED }

data class SaveStatus(
    val phase: SavePhase = SavePhase.IDLE,
    val uri: Uri? = null,
    val displayName: String = "",
    val message: String = "",
    val retryable: Boolean = false,
)
