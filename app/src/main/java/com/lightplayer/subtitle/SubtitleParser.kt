package com.lightplayer.subtitle

import kotlin.math.max

data class Cue(val startMs: Long, val endMs: Long, val text: String)

class SubtitleTrack(val cues: List<Cue>) {

    fun cueAt(ms: Long): Cue? {
        if (cues.isEmpty()) return null
        var lo = 0
        var hi = cues.size - 1
        var found: Cue? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = cues[mid]
            if (c.startMs <= ms) { found = c; lo = mid + 1 } else hi = mid - 1
        }
        return found?.takeIf { ms <= it.endMs }
    }
}

/**
 * Parses SRT, WebVTT and ASS/SSA subtitle files into a single sorted cue list.
 */
object SubtitleParser {

    fun parse(fileName: String, content: String): SubtitleTrack {
        val lower = fileName.lowercase()
        val clean = content
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace("\r", "\n")

        val cues = when {
            lower.endsWith(".ass") || lower.endsWith(".ssa") -> parseAss(clean)
            lower.endsWith(".vtt") || clean.startsWith("WEBVTT") -> parseVtt(clean)
            else -> parseSrt(clean)
        }
        return SubtitleTrack(
            cues.filter { it.text.isNotBlank() }.sortedBy { it.startMs }
        )
    }

    // ---------- SRT / VTT ----------

    private fun parseSrt(text: String): List<Cue> = parseArrowFormat(text)

    private fun parseVtt(text: String): List<Cue> = parseArrowFormat(text)

    private fun parseArrowFormat(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        val lines = text.split("\n")
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.contains("-->")) {
                val parts = line.split("-->")
                if (parts.size == 2) {
                    val start = parseTimestamp(parts[0])
                    val end = parseTimestamp(parts[1])
                    if (start != null && end != null) {
                        val sb = StringBuilder()
                        var j = i + 1
                        while (j < lines.size && lines[j].isNotBlank()) {
                            if (sb.isNotEmpty()) sb.append(' ')
                            sb.append(lines[j].trim())
                            j++
                        }
                        val txt = sanitize(sb.toString())
                        if (txt.isNotBlank()) out.add(Cue(start, max(end, start), txt))
                        i = j
                        continue
                    }
                }
            }
            i++
        }
        return out
    }

    // ---------- ASS / SSA ----------

    private fun parseAss(text: String): List<Cue> {
        val out = ArrayList<Cue>()
        for (raw in text.split("\n")) {
            val line = raw.trim()
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val body = line.substringAfter(':').trim()
            // Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            val parts = body.split(",", limit = 10)
            if (parts.size < 10) continue
            val start = parseTimestamp(parts[1]) ?: continue
            val end = parseTimestamp(parts[2]) ?: continue
            val txt = sanitize(parts[9])
            if (txt.isNotBlank()) out.add(Cue(start, max(end, start), txt))
        }
        return out
    }

    // ---------- helpers ----------

    /** Accepts HH:MM:SS.mmm, HH:MM:SS,mmm, MM:SS.mmm and ASS centiseconds (H:MM:SS.cc). */
    fun parseTimestamp(raw: String): Long? {
        val token = raw.trim().split(Regex("\\s+")).firstOrNull() ?: return null
        val parts = token.split(":")
        if (parts.size !in 2..3) return null

        fun fractionMs(secPart: String): Pair<Int, Long>? {
            val dot = secPart.indexOfFirst { it == '.' || it == ',' }
            val sec: Int
            val frac: String
            if (dot >= 0) {
                sec = secPart.substring(0, dot).toIntOrNull() ?: return null
                frac = secPart.substring(dot + 1)
            } else {
                sec = secPart.toIntOrNull() ?: return null
                frac = ""
            }
            val ms = when {
                frac.isEmpty() -> 0L
                frac.length == 1 -> frac.toLong() * 100
                frac.length == 2 -> frac.toLong() * 10          // ASS centiseconds
                else -> frac.substring(0, 3).toLong()           // milliseconds
            }
            return sec to ms
        }

        return try {
            when (parts.size) {
                3 -> {
                    val h = parts[0].toIntOrNull() ?: return null
                    val m = parts[1].toIntOrNull() ?: return null
                    val (s, ms) = fractionMs(parts[2]) ?: return null
                    ((h * 3600L) + (m * 60L) + s) * 1000L + ms
                }
                else -> {
                    val m = parts[0].toIntOrNull() ?: return null
                    val (s, ms) = fractionMs(parts[1]) ?: return null
                    (m * 60L + s) * 1000L + ms
                }
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun sanitize(raw: String): String {
        var t = raw
        t = t.replace(Regex("\\{[^}]*}"), "")          // ASS override tags
        t = t.replace(Regex("<[^>]+>"), "")            // HTML tags
        t = t.replace(Regex("\\\\N|\\\\n|\\\\h"), " ") // ASS line/space breaks
        t = t.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        return t.replace(Regex("\\s+"), " ").trim()
    }
}