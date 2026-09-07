package com.bnyro.recorder.util

import java.util.Locale

object TimeFormatHelper {
    fun formatDuration(seconds: Long): String {
        val s = if (seconds < 0) 0 else seconds
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        } else {
            String.format(Locale.US, "%02d:%02d", m, sec)
        }
    }
}
