package com.lightplayer.tts

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.lightplayer.subtitle.Cue
import com.lightplayer.util.MediaFiles
import com.lightplayer.util.SubtitleMatcher

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

            if (!MediaFiles.exists(context, Uri.parse(e.uri))) {
                index.remove(e.uri)      // file was deleted outside the app
                continue
            }
            val upgraded = if (legacy) e.copy(contentKey = ck) else e
            if (legacy) index.add(upgraded)   // exact matching from now on
            return upgraded
        }
        return findInMediaStore(context, index, subtitleName, ck, lastStart, lastEnd)
    }

    /**
     * The index can be lost (app data cleared, reinstall) while the audio is still in Music/SubTTS.
     * Tracks are named after their subtitle file, so look for "<subtitle name>.wav" there and
     * accept it when its length fits the subtitle's timing.
     */
    private fun findInMediaStore(
        context: Context, index: TtsIndex, subtitleName: String,
        ck: String, lastStart: Long, lastEnd: Long
    ): TtsEntry? {
        if (Build.VERSION.SDK_INT < 29) return null
        val base = SubtitleMatcher.baseName(subtitleName)
        if (base.isBlank()) return null
        return try {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(
                    MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_ADDED
                ),
                "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} = ?",
                arrayOf("%SubTTS%", TtsGenerator.fileNameFor(base)),
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val duration = c.getLong(2)
                    if (duration !in (lastStart + 300)..(lastEnd + 20_000)) continue
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(0))
                    if (!MediaFiles.exists(context, uri)) continue
                    val entry = TtsEntry(
                        key = ck, uri = uri.toString(), videoTitle = base, subtitleName = subtitleName,
                        createdAt = c.getLong(4) * 1000L, durationMs = duration, sizeBytes = c.getLong(3),
                        fileName = c.getString(1) ?: "", contentKey = ck
                    )
                    index.add(entry)
                    return@use entry
                }
                null
            }
        } catch (_: Throwable) {
            null
        }
    }
}
