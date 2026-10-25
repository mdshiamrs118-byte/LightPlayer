package com.lightplayer.util

import android.content.Context

/** Favorite videos, kept by their content URI. */
object FavoritesStore {

    private var cache: MutableSet<String>? = null

    @Synchronized
    private fun load(c: Context): MutableSet<String> =
        cache ?: Prefs.favorites(c).toMutableSet().also { cache = it }

    @Synchronized
    fun isFavorite(c: Context, uri: String): Boolean = load(c).contains(uri)

    @Synchronized
    fun all(c: Context): Set<String> = load(c).toSet()

    /** Returns true when the video is a favorite after the change. */
    @Synchronized
    fun toggle(c: Context, uri: String): Boolean {
        val set = load(c)
        val now = if (set.remove(uri)) false else { set.add(uri); true }
        Prefs.setFavorites(c, set)
        return now
    }

    @Synchronized
    fun remove(c: Context, uri: String) {
        val set = load(c)
        if (set.remove(uri)) Prefs.setFavorites(c, set)
    }
}
