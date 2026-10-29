package com.lightplayer.util

import android.content.Context
import com.lightplayer.subtitle.Cue
import com.lightplayer.subtitle.SubtitleTrack
import java.io.File

/**
 * Keeps the parsed lines of subtitles the user picked, plus which subtitle goes with which
 * video. A picked file can become unreadable later, so its text is kept inside the app.
 */
object SubtitleStore {

    private const val MAX_FILES = 150

    private fun dir(ctx: Context) = File(ctx.filesDir, "subtitles")

    private fun fileFor(ctx: Context, contentKey: String) = File(dir(ctx), "$contentKey.txt")

    /**
     * The keys that identify a video. The address of a picked file can differ from one
     * "Open file" to the next, so the file's name and size count as an identity too.
     */
    fun videoKeys(ctx: Context, uri: android.net.Uri): List<String> {
        val keys = ArrayList<String>(2)
        keys.add(uri.toString())
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    val size = if (c.isNull(1)) 0L else c.getLong(1)
                    if (!name.isNullOrBlank() && size > 0) keys.add("file:$name|$size")
                }
            }
        } catch (_: Throwable) {
        }
        return keys
    }

    /** Remembers [cues] and that each of [videoKeys] uses them. */
    fun remember(ctx: Context, videoKeys: List<String>, contentKey: String, name: String, cues: List<Cue>) {
        SubtitleCache.writeTo(fileFor(ctx, contentKey), name, cues)
        for (k in videoKeys) Prefs.setVideoSubtitle(ctx, k, contentKey, name)
        prune(ctx)
    }

    /** The subtitle remembered for a video (under any of its keys), or null. */
    fun recall(ctx: Context, videoKeys: List<String>): Triple<String, String, SubtitleTrack>? {
        for (k in videoKeys) {
            val (contentKey, _) = Prefs.videoSubtitle(ctx, k) ?: continue
            val (name, track) = SubtitleCache.readFrom(fileFor(ctx, contentKey)) ?: continue
            return Triple(contentKey, name, track)
        }
        return null
    }

    private fun prune(ctx: Context) {
        val files = dir(ctx).listFiles() ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES).forEach { it.delete() }
    }
}
