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
}
