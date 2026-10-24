package com.lightplayer.util

import android.content.Context

/** Remembers where each video was left off, so it can resume and show a progress bar. */
object PositionStore {

    class Entry(val pos: Long, val dur: Long, val time: Long)

    private const val FILE = "lightplayer_positions"
    private const val MAX_ENTRIES = 400
    private const val MIN_SAVE_MS = 5_000L

    private var map: HashMap<String, Entry>? = null

    private fun prefs(c: Context) =
        c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(c: Context): HashMap<String, Entry> {
        map?.let { return it }
        val m = HashMap<String, Entry>()
        for ((key, value) in prefs(c).all) {
            val parts = (value as? String)?.split('|') ?: continue
            if (parts.size < 3) continue
            val pos = parts[0].toLongOrNull() ?: continue
            val dur = parts[1].toLongOrNull() ?: continue
            val time = parts[2].toLongOrNull() ?: continue
            m[key] = Entry(pos, dur, time)
        }
        map = m
        return m
    }

    @Synchronized
    fun get(c: Context, uri: String): Entry? = load(c)[uri]

    /** Saves the position, or forgets it when the video is barely started or basically finished. */
    @Synchronized
    fun save(c: Context, uri: String, pos: Long, dur: Long) {
        val finished = dur > 0 && pos >= dur * 0.95
        if (pos < MIN_SAVE_MS || finished) {
            clear(c, uri)
            return
        }
        val m = load(c)
        val entry = Entry(pos, dur, System.currentTimeMillis())
        m[uri] = entry
        val editor = prefs(c).edit().putString(uri, "$pos|$dur|${entry.time}")
        if (m.size > MAX_ENTRIES) {
            val oldest = m.entries.sortedBy { it.value.time }.take(m.size - MAX_ENTRIES).map { it.key }
            oldest.forEach { m.remove(it); editor.remove(it) }
        }
        editor.apply()
    }

    @Synchronized
    fun clear(c: Context, uri: String) {
        load(c).remove(uri)
        prefs(c).edit().remove(uri).apply()
    }

    /** 0..1 how much of the video was watched (0 when unknown). */
    fun fraction(c: Context, uri: String, fallbackDurationMs: Long): Float {
        val e = get(c, uri) ?: return 0f
        val d = if (e.dur > 0) e.dur else fallbackDurationMs
        return if (d > 0) (e.pos.toFloat() / d).coerceIn(0f, 1f) else 0f
    }
}
