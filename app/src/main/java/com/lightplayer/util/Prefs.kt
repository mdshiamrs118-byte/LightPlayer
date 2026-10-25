package com.lightplayer.util

import android.content.Context

object Prefs {
    private const val NAME = "light_player_settings"

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun rate(context: Context): Float = sp(context).getFloat("rate", 1.0f)
    fun setRate(context: Context, v: Float) { sp(context).edit().putFloat("rate", v).apply() }

    fun pitch(context: Context): Float = sp(context).getFloat("pitch", 1.0f)
    fun setPitch(context: Context, v: Float) { sp(context).edit().putFloat("pitch", v).apply() }

    fun engine(context: Context): String? = sp(context).getString("engine", null)
    fun setEngine(context: Context, pkg: String?) { sp(context).edit().putString("engine", pkg).apply() }

    // ---- appearance ----
    fun themeMode(context: Context): String = sp(context).getString("theme_mode", "system") ?: "system"
    fun setThemeMode(context: Context, v: String) { sp(context).edit().putString("theme_mode", v).apply() }

    fun themePreset(context: Context): String = sp(context).getString("theme_preset", "Ocean") ?: "Ocean"
    fun setThemePreset(context: Context, v: String) { sp(context).edit().putString("theme_preset", v).apply() }

    fun themeHue(context: Context): Float = sp(context).getFloat("theme_hue", 205f)
    fun setThemeHue(context: Context, v: Float) { sp(context).edit().putFloat("theme_hue", v).apply() }

    /** "pill" (floating) or "classic" (full-width bottom bar). */
    fun navStyle(context: Context): String = sp(context).getString("nav_style", "pill") ?: "pill"
    fun setNavStyle(context: Context, v: String) { sp(context).edit().putString("nav_style", v).apply() }

    // ---- playback ----
    fun resumeEnabled(context: Context): Boolean = sp(context).getBoolean("resume_position", true)
    fun setResumeEnabled(context: Context, v: Boolean) { sp(context).edit().putBoolean("resume_position", v).apply() }

    fun autoNext(context: Context): Boolean = sp(context).getBoolean("auto_next", true)
    fun setAutoNext(context: Context, v: Boolean) { sp(context).edit().putBoolean("auto_next", v).apply() }

    fun backgroundAudio(context: Context): Boolean = sp(context).getBoolean("background_audio", false)
    fun setBackgroundAudio(context: Context, v: Boolean) { sp(context).edit().putBoolean("background_audio", v).apply() }

    fun playbackSpeed(context: Context): Float = sp(context).getFloat("playback_speed", 1f)
    fun setPlaybackSpeed(context: Context, v: Float) { sp(context).edit().putFloat("playback_speed", v).apply() }

    /** Ids of the player-menu actions pinned next to the menu button, in order. */
    fun pinnedActions(context: Context): List<String> =
        (sp(context).getString("pinned_actions", "") ?: "").split(',').filter { it.isNotBlank() }

    fun setPinnedActions(context: Context, ids: List<String>) {
        sp(context).edit().putString("pinned_actions", ids.joinToString(",")).apply()
    }

    // ---- floating window (custom picture-in-picture) ----
    fun autoPip(context: Context): Boolean = sp(context).getBoolean("auto_pip", false)
    fun setAutoPip(context: Context, v: Boolean) { sp(context).edit().putBoolean("auto_pip", v).apply() }

    /** Width in pixels of the floating window (0 = not chosen yet). */
    fun pipWidth(context: Context): Int = sp(context).getInt("pip_width", 0)
    fun pipX(context: Context): Int = sp(context).getInt("pip_x", -1)
    fun pipY(context: Context): Int = sp(context).getInt("pip_y", -1)
    fun setPipGeometry(context: Context, width: Int, x: Int, y: Int) {
        sp(context).edit().putInt("pip_width", width).putInt("pip_x", x).putInt("pip_y", y).apply()
    }

    // ---- library ----
    fun videoSort(context: Context): String = sp(context).getString("video_sort", "NAME") ?: "NAME"
    fun setVideoSort(context: Context, v: String) { sp(context).edit().putString("video_sort", v).apply() }

    // ---- subtitles ----
    fun subtitleSize(context: Context): Float = sp(context).getFloat("sub_size", 17f)
    fun setSubtitleSize(context: Context, v: Float) { sp(context).edit().putFloat("sub_size", v).apply() }

    fun subtitleColor(context: Context): Int = sp(context).getInt("sub_color", 0xFFFFFFFF.toInt())
    fun setSubtitleColor(context: Context, v: Int) { sp(context).edit().putInt("sub_color", v).apply() }

    /** 0 = none, 1 = dim, 2 = solid black. */
    fun subtitleBackground(context: Context): Int = sp(context).getInt("sub_bg", 1)
    fun setSubtitleBackground(context: Context, v: Int) { sp(context).edit().putInt("sub_bg", v).apply() }

    fun autoSubtitles(context: Context): Boolean = sp(context).getBoolean("auto_subtitles", true)
    fun setAutoSubtitles(context: Context, v: Boolean) { sp(context).edit().putBoolean("auto_subtitles", v).apply() }

    /** Subtitle sync offset (ms) remembered per video. */
    fun subtitleOffset(context: Context, videoKey: String): Long = sp(context).getLong("suboff:$videoKey", 0L)
    fun setSubtitleOffset(context: Context, videoKey: String, ms: Long) {
        val e = sp(context).edit()
        if (ms == 0L) e.remove("suboff:$videoKey") else e.putLong("suboff:$videoKey", ms)
        e.apply()
    }

    /** Folders (document-tree URIs) the user chose to look for subtitle files in. */
    fun subtitleFolders(context: Context): Set<String> =
        sp(context).getStringSet("subtitle_folders", emptySet())?.toSet() ?: emptySet()
    fun setSubtitleFolders(context: Context, v: Set<String>) {
        sp(context).edit().putStringSet("subtitle_folders", HashSet(v)).apply()
    }

    // ---- favorites ----
    fun favorites(context: Context): Set<String> =
        sp(context).getStringSet("favorites", emptySet())?.toSet() ?: emptySet()
    fun setFavorites(context: Context, v: Set<String>) {
        sp(context).edit().putStringSet("favorites", HashSet(v)).apply()
    }
}
