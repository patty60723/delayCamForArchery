package com.archery.delaycam.state

/**
 * Mirrors the web prototype's APP_STATE machine: gestures/manual-save are
 * only accepted in LIVE (spec 2.2 - new triggers during SAVING/REPLAY are
 * ignored until back to live playback).
 */
enum class AppState { LIVE, SAVING, REPLAY }

/** Compose navigation destinations (spec 3.1-3.4). */
object Routes {
    const val LIVE = "live"
    const val REPLAY = "replay"
    const val SETTINGS = "settings"
    const val VIEWER = "viewer"
    const val GALLERY = "gallery"
    const val PLAYER = "player/{clipUri}/{clipName}"

    fun player(clipUri: String, clipName: String): String {
        val encodedUri = java.net.URLEncoder.encode(clipUri, "UTF-8")
        val encodedName = java.net.URLEncoder.encode(clipName, "UTF-8")
        return "player/$encodedUri/$encodedName"
    }
}
