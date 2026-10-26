package com.lightplayer.util

import android.content.Context
import com.lightplayer.subtitle.Cue
import com.lightplayer.subtitle.SubtitleTrack
import java.io.File

/**
 * Hands an already-parsed subtitle between the player screen and the floating window. The file
 * the subtitle came from may not stay readable once the player screen closes, so the parsed
 * lines are kept in a small cache file instead.
 */
object SubtitleCache {

    private const val FILE = "pip_subtitle.txt"

    fun save(ctx: Context, name: String, cues: List<Cue>) {
        try {
            File(ctx.cacheDir, FILE).bufferedWriter().use { w ->
                w.write(name.replace('\n', ' '))
                w.newLine()
                for (c in cues) {
                    w.write("${c.startMs}\t${c.endMs}\t${escape(c.text)}")
                    w.newLine()
                }
            }
        } catch (_: Throwable) {
        }
    }

    fun load(ctx: Context): Pair<String, SubtitleTrack>? {
        val file = File(ctx.cacheDir, FILE)
        if (!file.exists()) return null
        return try {
            file.bufferedReader().use { r ->
                val name = r.readLine() ?: return null
                val cues = ArrayList<Cue>()
                r.forEachLine { line ->
                    val parts = line.split('\t', limit = 3)
                    if (parts.size == 3) {
                        val start = parts[0].toLongOrNull()
                        val end = parts[1].toLongOrNull()
                        if (start != null && end != null) cues.add(Cue(start, end, unescape(parts[2])))
                    }
                }
                if (cues.isEmpty()) null else name to SubtitleTrack(cues)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\n", "\\n").replace("\t", " ").replace("\r", "")

    private fun unescape(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                out.append(if (text[i + 1] == 'n') '\n' else text[i + 1])
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
