package com.lightplayer.util

import android.content.Context
import com.lightplayer.tts.TtsGenerator
import java.io.File

/**
 * Remembers which subtitle file the user picked for each video, so reopening the video brings
 * the same subtitle (and its already generated speech) back without picking it again.
 *
 * A copy of the subtitle text is kept inside the app: the original file may be in a place the
 * app can no longer read after it restarts.
 */
object SubtitleMemory {

    private const val DIR = "video_subtitles"

    class Saved(val name: String, val text: String)

    /** Same file, however it was opened (library or "open file"): display name + size. */
    fun videoId(displayName: String, sizeBytes: Long): String = "${displayName.lowercase()}|$sizeBytes"

    private fun fileFor(ctx: Context, videoId: String): File =
        File(File(ctx.applicationContext.filesDir, DIR), TtsGenerator.buildKey(listOf(videoId)) + ".txt")

    fun save(ctx: Context, videoId: String, name: String, text: String) {
        try {
            val f = fileFor(ctx, videoId)
            f.parentFile?.mkdirs()
            f.writeText(name.replace('\n', ' ').replace('\r', ' ') + "\n" + text)
        } catch (_: Throwable) {
        }
    }

    fun load(ctx: Context, videoId: String): Saved? {
        return try {
            val f = fileFor(ctx, videoId)
            if (!f.exists()) return null
            val all = f.readText()
            val nl = all.indexOf('\n')
            if (nl <= 0) null else Saved(all.substring(0, nl), all.substring(nl + 1))
        } catch (_: Throwable) {
            null
        }
    }
}
