package com.archery.delaycam.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Matches spec 6: archery_yyyyMMdd_HHmmss.mp4 */
fun clipDisplayName(date: Date = Date()): String {
    val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    return "archery_${formatter.format(date)}.mp4"
}
