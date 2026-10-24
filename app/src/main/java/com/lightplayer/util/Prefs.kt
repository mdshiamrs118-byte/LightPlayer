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
}
