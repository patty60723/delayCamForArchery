package com.archery.delaycam.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "delaycam_settings")

/** DataStore-backed persistence, the native equivalent of the web prototype's settingsStore.js. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val DELAY_SEC = intPreferencesKey("delaySec")
        val BACKTRACK_SEC = intPreferencesKey("backtrackSec")
        val GUIDE_V = booleanPreferencesKey("guideVertical")
        val GUIDE_H = booleanPreferencesKey("guideHorizontal")
        val SLOW_MO_RATE = floatPreferencesKey("slowMoRate")
        val PREVIEW_CORNER = stringPreferencesKey("previewCorner")
        val DOMINANT_HAND = stringPreferencesKey("dominantHand")
        val PICTURE_MODE = stringPreferencesKey("pictureMode")
        val CAPTURE_ASPECT = stringPreferencesKey("captureAspect")
    }

    val settingsFlow: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            captureAspect = if (prefs[Keys.CAPTURE_ASPECT] == "SIXTEEN_NINE") CaptureAspect.SIXTEEN_NINE else CaptureAspect.FOUR_THREE,
            pictureMode = if (prefs[Keys.PICTURE_MODE] == "FILL") PictureMode.FILL else PictureMode.FIT,
            delaySec = prefs[Keys.DELAY_SEC] ?: 5,
            backtrackSec = prefs[Keys.BACKTRACK_SEC] ?: 8,
            guideVertical = prefs[Keys.GUIDE_V] ?: true,
            guideHorizontal = prefs[Keys.GUIDE_H] ?: true,
            slowMoRate = prefs[Keys.SLOW_MO_RATE] ?: 0.5f,
            previewCorner = when (prefs[Keys.PREVIEW_CORNER]) {
                "BOTTOM_LEFT" -> PreviewCorner.BOTTOM_LEFT
                "BOTTOM_RIGHT" -> PreviewCorner.BOTTOM_RIGHT
                else -> if (prefs[Keys.DOMINANT_HAND] == "RIGHT") PreviewCorner.BOTTOM_LEFT
                    else PreviewCorner.BOTTOM_RIGHT
            },
            dominantHand = when (prefs[Keys.DOMINANT_HAND]) {
                "LEFT" -> DominantHand.LEFT
                else -> DominantHand.RIGHT
            },
        )
    }

    suspend fun save(settings: AppSettings) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.DELAY_SEC] = settings.delaySec
            prefs[Keys.BACKTRACK_SEC] = settings.backtrackSec
            prefs[Keys.GUIDE_V] = settings.guideVertical
            prefs[Keys.GUIDE_H] = settings.guideHorizontal
            prefs[Keys.SLOW_MO_RATE] = settings.slowMoRate
            prefs[Keys.PREVIEW_CORNER] = settings.previewCorner.name
            prefs[Keys.DOMINANT_HAND] = settings.dominantHand.name
            prefs[Keys.PICTURE_MODE] = settings.pictureMode.name
            prefs[Keys.CAPTURE_ASPECT] = settings.captureAspect.name
        }
    }
}

