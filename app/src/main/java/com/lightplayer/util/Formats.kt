package com.lightplayer.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Formats {

    fun time(ms: Long): String {
        if (ms <= 0) return "0:00"
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun size(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val gb = bytes / 1024.0 / 1024.0 / 1024.0
        val mb = bytes / 1024.0 / 1024.0
        val kb = bytes / 1024.0
        return when {
            gb >= 1 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1 -> String.format(Locale.US, "%.1f MB", mb)
            else -> String.format(Locale.US, "%.0f KB", kb)
        }
    }

    fun date(epochSeconds: Long): String {
        if (epochSeconds <= 0) return "—"
        return SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
            .format(Date(epochSeconds * 1000))
    }
}