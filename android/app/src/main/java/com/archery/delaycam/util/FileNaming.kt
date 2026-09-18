package com.archery.delaycam.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun timestamp(date: Date): String =
    SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(date)

/** Matches spec 6: archery_yyyyMMdd_HHmmss.mp4 */
fun clipDisplayName(date: Date = Date()): String = "archery_${timestamp(date)}.mp4"

/** Continuous-recording sessions (see camera/ContinuousRecordingSession.kt) get
 *  their own prefix so they're visually distinct from per-shot clips in the gallery. */
fun sessionDisplayName(date: Date = Date()): String = "archery_session_${timestamp(date)}.mp4"
