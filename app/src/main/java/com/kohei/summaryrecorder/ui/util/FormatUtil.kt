package com.kohei.summaryrecorder.ui.util

import androidx.datastore.preferences.core.floatPreferencesKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FormatUtil {
    private val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

    fun formatDate(timestamp: Long): String =
        dateFormat.format(Date(timestamp))

    fun formatDuration(ms: Long): String {
        val totalSeconds = ms / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    fun formatTimer(seconds: Int): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}

val PLAYBACK_SPEED_KEY = floatPreferencesKey("playback_speed")
val SPEED_CYCLE = floatArrayOf(1.0f, 1.2f, 1.5f, 2.0f, 0.5f, 0.8f)
