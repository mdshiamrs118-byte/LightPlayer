package com.lightplayer.util

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.lightplayer.subtitle.SubtitleParser

/** Reads a picked subtitle file (name + decoded text). */
object SubtitleReader {

    private const val MAX_BYTES = 8L * 1024 * 1024

    fun displayName(context: Context, uri: Uri): String {
        var name: String? = null
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
        } catch (_: Throwable) {}
        return name ?: uri.lastPathSegment ?: "subtitle.srt"
    }

    /** Decoded text, or null when the file is unreadable or larger than 8 MB. */
    fun readText(context: Context, uri: Uri): String? = try {
        var size = 0L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0)
        }
        if (size > MAX_BYTES) null
        else context.contentResolver.openInputStream(uri)?.use { SubtitleParser.decode(it.readBytes()) }
    } catch (_: Throwable) {
        null
    }
}
