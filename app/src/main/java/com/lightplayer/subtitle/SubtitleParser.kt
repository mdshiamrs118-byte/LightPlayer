package com.lightplayer.subtitle

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
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

    /** Decodes raw subtitle bytes: UTF-16 (BOM or no BOM), UTF-8, else Windows-1252. */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 2) {
            val b0 = bytes[0].toInt() and 0xFF
            val b1 = bytes[1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
            if (b0 == 0xFE && b1 == 0xFF) return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 4) {
            // UTF-16 without BOM: ASCII text interleaved with zero bytes.
            if (bytes[1].toInt() == 0 && bytes[3].toInt() == 0 && bytes[0].toInt() != 0) {
                return String(bytes, Charsets.UTF_16LE)
            }
            if (bytes[0].toInt() == 0 && bytes[2].toInt() == 0 && bytes[1].toInt() != 0) {
                return String(bytes, Charsets.UTF_16BE)
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
    }

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
            cues.filter { it.text.isNotBlank() && !isDrawing(it.text) }.sortedBy { it.startMs }
        )
    }

    /** ASS vector drawings ("m 0 0 l 100 0 ...") sometimes leak into SRT/VTT conversions. */
    private fun isDrawing(text: String): Boolean {
        val tokens = text.trim().split(' ').filter { it.isNotEmpty() }
        if (tokens.size < 3) return false
        val first = tokens[0]
        if (first != "m" && first != "n") return false
        val commands = setOf("m", "n", "l", "b", "s", "c", "p")
        return tokens.all { it in commands || it.toDoubleOrNull() != null }
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
        var startIdx = 1
        var endIdx = 2
        var textIdx = 9
        var fieldCount = 10
        var inEvents = false
        for (raw in text.split("\n")) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (inEvents && line.startsWith("Format:", ignoreCase = true)) {
                val names = line.substringAfter(':').split(",").map { it.trim().lowercase() }
                val s = names.indexOf("start")
                val e = names.indexOf("end")
                val t = names.indexOf("text")
                if (s >= 0 && e >= 0 && t >= 0) {
                    startIdx = s
                    endIdx = e
                    textIdx = t
                    fieldCount = names.size
                }
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            val body = line.substringAfter(':').trim()
            val parts = body.split(",", limit = fieldCount)
            if (parts.size < fieldCount) continue
            val start = parseTimestamp(parts[startIdx]) ?: continue
            val end = parseTimestamp(parts[endIdx]) ?: continue
            val txt = sanitize(parts[textIdx])
            if (txt.isNotBlank()) out.add(Cue(start, max(end, start), txt))
        }
        return out
    }

    // ---------- helpers ----------

    /** Accepts HH:MM:SS.mmm, HH:MM:SS,mmm, MM:SS.mmm and ASS centiseconds (H:MM:SS.cc). */
    fun parseTimestamp(raw: String): Long? {
        val token = raw.trim().split(' ', '\t').firstOrNull() ?: return null
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

    /** Removes everything from [open] to the next [close] (ASS {tags}, HTML <tags>). */
    private fun stripBetween(s: String, open: Char, close: Char): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == open) {
                val j = s.indexOf(close, i + 1)
                if (j >= 0) {
                    i = j + 1
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun sanitize(raw: String): String {
        var t = stripBetween(raw, '{', '}')
        t = stripBetween(t, '<', '>')
        t = t.replace("\\N", " ").replace("\\n", " ").replace("\\h", " ")
        t = t.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        return t.split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }.joinToString(" ")
    }
}
