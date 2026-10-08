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
}