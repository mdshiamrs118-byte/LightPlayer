package com.lightplayer.tts

import android.content.Context
import android.net.Uri
import com.lightplayer.subtitle.Cue

/**
 * Finds audio that was already generated for a subtitle, so it is never made twice.
 *
 * The spoken audio depends only on the subtitle lines and their timing (not on the video),
 * so tracks are matched by a hash of the subtitle content. Tracks made by older versions of
 * the app have no hash; those are matched by subtitle file name plus a sanity check of the
 * audio length against the subtitle's timing.
 */
object TtsCache {

    fun contentKey(cues: List<Cue>): String {
        val parts = ArrayList<String>(cues.size + 1)
        parts.add("subtitle-v1")
        for (c in cues) {
            if (c.text.isNotBlank()) parts.add("${c.startMs}:${c.endMs}:${c.text.trim()}")
        }
        return TtsGenerator.buildKey(parts)
    }

    /** Returns a still-existing track for this subtitle, or null. Does file I/O: call off the main thread when possible. */
    fun find(context: Context, index: TtsIndex, subtitleName: String, cues: List<Cue>): TtsEntry? {
        val spoken = cues.filter { it.text.isNotBlank() }
        if (spoken.isEmpty()) return null
        val ck = contentKey(cues)
        val lastStart = spoken.maxOf { it.startMs }
        val lastEnd = spoken.maxOf { it.endMs }

        for (e in index.all().sortedByDescending { it.createdAt }) {
            val exact = e.contentKey.isNotEmpty() && e.contentKey == ck
            val legacy = e.contentKey.isEmpty() &&
                e.subtitleName.equals(subtitleName, ignoreCase = true) &&
                e.durationMs in (lastStart + 300)..(lastEnd + 20_000)
            if (!exact && !legacy) continue

            if (!exists(context, e.uri)) {
                index.remove(e.uri)      // file was deleted outside the app
                continue
            }
            val upgraded = if (legacy) e.copy(contentKey = ck) else e
            if (legacy) index.add(upgraded)   // exact matching from now on
            return upgraded
        }
        return null
    }

    private fun exists(context: Context, uri: String): Boolean = try {
        context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.use { true } ?: false
    } catch (_: Throwable) {
        false
    }
}
