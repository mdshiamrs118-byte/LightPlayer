package com.lightplayer.util

import android.content.Context

/**
 * Remembers video lengths Android's media library could not read (e.g. videos hidden behind a
 * fake image header), so the "0:00" badge goes away once the length is known.
 */
object DurationCache {

    private const val FILE = "lightplayer_durations"
    private const val MAX = 1000
    private var map: HashMap<String, Long>? = null

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(c: Context): HashMap<String, Long> {
        map?.let { return it }
        val m = HashMap<String, Long>()
        for ((k, v) in prefs(c).all) (v as? Long)?.let { m[k] = it }
        map = m
        return m
    }

    @Synchronized
    fun get(c: Context, uri: String): Long = load(c)[uri] ?: 0L

    @Synchronized
    fun put(c: Context, uri: String, durationMs: Long) {
        if (durationMs <= 0L) return
        val m = load(c)
        if (m[uri] == durationMs) return
        m[uri] = durationMs
        val e = prefs(c).edit().putLong(uri, durationMs)
        if (m.size > MAX) {
            m.keys.take(m.size - MAX).toList().forEach { m.remove(it); e.remove(it) }
        }
        e.apply()
    }

    @Synchronized
    fun remove(c: Context, uri: String) {
        if (load(c).remove(uri) != null) prefs(c).edit().remove(uri).apply()
    }
}
