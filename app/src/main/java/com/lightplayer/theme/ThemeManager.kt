package com.lightplayer.theme

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lightplayer.R
import com.lightplayer.util.Prefs
import kotlin.math.abs

/** Every color the UI uses, generated from a mode + hue. */
data class Palette(
    val isLight: Boolean,
    val bgTop: Int,
    val bgBottom: Int,
    val surface: Int,
    val stroke: Int,
    val accent: Int,
    val onAccent: Int,
    val container: Int,
    val onContainer: Int,
    val text1: Int,
    val text2: Int,
    val accent2: Int,
    val navCapsule: Int
)

/**
 * Runtime theming: Mode (System / Light / Dark / Black) + color preset or custom hue.
 *
 * Views opt in with an android:tag (see [apply]); one call recolors a whole screen,
 * so the theme can change live without restarting anything.
 */
object ThemeManager {

    const val MODE_SYSTEM = "system"
    const val MODE_LIGHT = "light"
    const val MODE_DARK = "dark"
    const val MODE_BLACK = "black"
    const val CUSTOM = "Custom"

    class Preset(val name: String, val hue: Float, val mono: Boolean = false)

    val presets = listOf(
        Preset("Sunset", 8f),
        Preset("Forest", 140f),
        Preset("Ocean", 205f),
        Preset("Violet", 272f),
        Preset("Rose", 338f),
        Preset("Mono", 0f, mono = true)
    )

    /** Bumped on every change so list items know when their colors are stale. */
    @Volatile var version = 1
        private set

    private var cacheKey: String? = null
    private var cachePalette: Palette? = null

    // ---------- settings ----------

    fun mode(ctx: Context) = Prefs.themeMode(ctx)
    fun preset(ctx: Context) = Prefs.themePreset(ctx)
    fun hue(ctx: Context) = Prefs.themeHue(ctx)

    fun setMode(ctx: Context, mode: String) {
        Prefs.setThemeMode(ctx, mode); version++
    }

    fun setPreset(ctx: Context, name: String) {
        val p = presets.firstOrNull { it.name == name } ?: return
        Prefs.setThemePreset(ctx, p.name)
        Prefs.setThemeHue(ctx, p.hue)
        version++
    }

    fun setCustomHue(ctx: Context, hue: Float) {
        Prefs.setThemePreset(ctx, CUSTOM)
        Prefs.setThemeHue(ctx, hue)
        version++
    }

    /** Marks every cached row color as stale (e.g. when the system day/night setting flips). */
    fun bump() { version++ }

    // ---------- palette ----------

    fun palette(ctx: Context, forceDark: Boolean = false): Palette {
        val mode = if (forceDark) MODE_DARK else mode(ctx)
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val preset = preset(ctx)
        val hue = hue(ctx)
        val key = "$mode|$night|$preset|$hue|$forceDark"
        if (key == cacheKey) cachePalette?.let { return it }

        val mono = preset == "Mono"
        val effective = when (mode) {
            MODE_SYSTEM -> if (night) MODE_DARK else MODE_LIGHT
            else -> mode
        }
        val p = build(hue, mono, effective)
        cacheKey = key
        cachePalette = p
        return p
    }

    private fun build(h: Float, mono: Boolean, mode: String): Palette {
        fun s(x: Float) = if (mono) 0f else x
        return when (mode) {
            MODE_LIGHT -> Palette(
                isLight = true,
                bgTop = hsl(h, s(0.85f), if (mono) 0.975f else 0.89f),
                bgBottom = hsl(h, s(0.75f), 0.975f),
                surface = hsl(h, s(0.55f), if (mono) 0.95f else 0.925f),
                stroke = hsl(h, s(0.40f), if (mono) 0.88f else 0.80f),
                accent = hsl(h, s(0.55f), if (mono) 0.55f else 0.30f),
                onAccent = Color.WHITE,
                container = if (mono) Color.WHITE else hsl(h, 0.95f, 0.80f),
                onContainer = hsl(h, s(0.60f), if (mono) 0.35f else 0.25f),
                text1 = hsl(h, s(0.50f), if (mono) 0.15f else 0.10f),
                text2 = hsl(h, s(0.25f), if (mono) 0.42f else 0.38f),
                accent2 = hsl(h + 35f, s(0.70f), if (mono) 0.45f else 0.35f),
                navCapsule = Color.WHITE
            )
            MODE_BLACK -> Palette(
                isLight = false,
                bgTop = Color.BLACK,
                bgBottom = Color.BLACK,
                surface = hsl(h, s(0.15f), 0.09f),
                stroke = hsl(h, s(0.15f), 0.17f),
                accent = hsl(h, s(0.55f), if (mono) 0.85f else 0.68f),
                onAccent = hsl(h, s(0.60f), if (mono) 0.08f else 0.10f),
                container = hsl(h, s(0.40f), if (mono) 0.30f else 0.20f),
                onContainer = hsl(h, s(0.70f), if (mono) 0.93f else 0.88f),
                text1 = hsl(h, s(0.45f), 0.95f),
                text2 = hsl(h, s(0.15f), 0.70f),
                accent2 = hsl(h + 35f, s(0.60f), 0.70f),
                navCapsule = hsl(h, s(0.60f), if (mono) 0.08f else 0.10f)
            )
            else -> Palette(
                isLight = false,
                bgTop = hsl(h, s(0.28f), 0.085f),
                bgBottom = hsl(h, s(0.28f), 0.085f),
                surface = hsl(h, s(0.20f), if (mono) 0.12f else 0.15f),
                stroke = hsl(h, s(0.18f), 0.24f),
                accent = hsl(h, s(0.55f), if (mono) 0.85f else 0.72f),
                onAccent = hsl(h, s(0.60f), if (mono) 0.08f else 0.10f),
                container = hsl(h, s(0.45f), if (mono) 0.32f else 0.22f),
                onContainer = hsl(h, s(0.70f), if (mono) 0.93f else 0.90f),
                text1 = hsl(h, s(0.45f), 0.94f),
                text2 = hsl(h, s(0.15f), 0.70f),
                accent2 = hsl(h + 35f, s(0.60f), 0.72f),
                navCapsule = hsl(h, s(0.60f), if (mono) 0.08f else 0.10f)
            )
        }
    }

    private fun hsl(hDeg: Float, s: Float, l: Float): Int {
        val h = ((hDeg % 360f) + 360f) % 360f
        val c = (1f - abs(2f * l - 1f)) * s
        val hp = h / 60f
        val x = c * (1f - abs(hp % 2f - 1f))
        val (r1, g1, b1) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        fun ch(v: Float) = ((v + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return Color.rgb(ch(r1), ch(g1), ch(b1))
    }

    // ---------- drawables ----------

    fun dp(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density

    fun rounded(ctx: Context, fill: Int, radiusDp: Float, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp)
            setColor(fill)
            if (stroke != null) setStroke(dp(ctx, 1f).toInt().coerceAtLeast(1), stroke)
        }

    fun background(p: Palette): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(p.bgTop, p.bgBottom))

    // ---------- applying ----------

    /** Recolors [view] and everything under it according to each view's android:tag. */
    fun apply(view: View, p: Palette) {
        val ctx = view.context
        when (view.tag as? String) {
            "bg" -> view.background = background(p)
            "card" -> view.background = rounded(ctx, p.surface, 22f, p.stroke)
            "banner" -> {
                view.background = rounded(ctx, p.container, 14f)
                (view as? TextView)?.setTextColor(p.onContainer)
            }
            "chip" -> {
                view.background = rounded(ctx, p.container, 40f)
                (view as? TextView)?.setTextColor(p.onContainer)
            }
            "thumb" -> view.background = rounded(ctx, p.container, 12f)
            "input" -> (view as? TextView)?.let {
                it.setTextColor(p.onContainer)
                it.setHintTextColor(p.text2)
            }
            "text1" -> (view as? TextView)?.setTextColor(p.text1)
            "text2" -> (view as? TextView)?.setTextColor(p.text2)
            "accent_text" -> (view as? TextView)?.setTextColor(p.accent)
            "icon_accent" -> (view as? ImageView)?.imageTintList = ColorStateList.valueOf(p.accent)
            "icon_text1" -> (view as? ImageView)?.imageTintList = ColorStateList.valueOf(p.text1)
            "icon_text2" -> (view as? ImageView)?.imageTintList = ColorStateList.valueOf(p.text2)
            "icon_tts" -> {
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(p.container)
                }
                (view as? ImageView)?.imageTintList = ColorStateList.valueOf(p.accent2)
            }
            "seek" -> tintSeek(view, p.accent, p.container)
            "seek_tts" -> tintSeek(view, p.accent2, p.container)
            "progress" -> (view as? ProgressBar)?.let {
                it.indeterminateTintList = ColorStateList.valueOf(p.accent)
                it.progressTintList = ColorStateList.valueOf(p.accent)
                it.progressBackgroundTintList = ColorStateList.valueOf(p.container)
            }
            "divider" -> view.setBackgroundColor(p.stroke)
            "switch" -> (view as? SwitchCompat)?.let {
                val checked = intArrayOf(android.R.attr.state_checked)
                val other = intArrayOf()
                it.thumbTintList = ColorStateList(arrayOf(checked, other), intArrayOf(p.onAccent, p.text2))
                it.trackTintList = ColorStateList(arrayOf(checked, other), intArrayOf(p.accent, p.container))
            }
            "btn" -> (view as? MaterialButton)?.let {
                it.backgroundTintList = ColorStateList.valueOf(p.accent)
                it.setTextColor(p.onAccent)
                it.iconTint = ColorStateList.valueOf(p.onAccent)
            }
            "btn_outline" -> (view as? MaterialButton)?.let {
                it.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                it.setTextColor(p.accent)
                it.strokeColor = ColorStateList.valueOf(p.accent)
                it.iconTint = ColorStateList.valueOf(p.accent)
            }
            "fab" -> (view as? MaterialButton)?.let {
                it.backgroundTintList = ColorStateList.valueOf(p.accent)
                it.setTextColor(p.onAccent)
                it.iconTint = ColorStateList.valueOf(p.onAccent)
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) apply(view.getChildAt(i), p)
        }
    }

    private fun tintSeek(view: View, accent: Int, track: Int) {
        (view as? SeekBar)?.let {
            it.progressTintList = ColorStateList.valueOf(accent)
            it.thumbTintList = ColorStateList.valueOf(accent)
            it.progressBackgroundTintList = ColorStateList.valueOf(track)
        }
    }

    /** Re-themes a whole screen after a live change, and makes lists rebuild their rows. */
    fun applyTree(root: View, p: Palette) {
        apply(root, p)
        refreshLists(root)
    }

    private fun refreshLists(view: View) {
        if (view is RecyclerView) {
            view.recycledViewPool.clear()
            view.adapter?.notifyDataSetChanged()
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) refreshLists(view.getChildAt(i))
        }
    }

    /** For list rows: recolors a row only if the theme changed since it was last colored. */
    fun ensure(row: View) {
        val seen = row.getTag(R.id.tag_theme_version) as? Int
        if (seen != version) {
            apply(row, palette(row.context))
            row.setTag(R.id.tag_theme_version, version)
        }
    }

    /** Window background, status bar and navigation bar. */
    fun applyWindow(activity: Activity, p: Palette) {
        val w = activity.window
        w.setBackgroundDrawable(background(p))
        w.statusBarColor = p.bgTop
        w.navigationBarColor = p.bgBottom
        val controller = WindowCompat.getInsetsController(w, w.decorView)
        controller.isAppearanceLightStatusBars = p.isLight
        controller.isAppearanceLightNavigationBars = p.isLight
    }

    // ---------- dialogs ----------

    fun dialogBuilder(ctx: Context, forceDark: Boolean = false): MaterialAlertDialogBuilder {
        val light = !forceDark && palette(ctx).isLight
        val themed = ContextThemeWrapper(
            ctx,
            if (light) R.style.Theme_LightPlayer_Light else R.style.Theme_LightPlayer
        )
        return MaterialAlertDialogBuilder(themed)
    }

    /** Rounded themed dialog surface, text and button colors. Returns the same dialog for chaining. */
    fun themed(dialog: AlertDialog, forceDark: Boolean = false): AlertDialog {
        dialog.setOnShowListener {
            val ctx = dialog.context
            val p = palette(ctx, forceDark)
            dialog.window?.setBackgroundDrawable(
                InsetDrawable(rounded(ctx, p.surface, 28f, p.stroke), dp(ctx, 12f).toInt())
            )
            dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(p.text1)
            dialog.findViewById<TextView>(android.R.id.message)?.setTextColor(p.text2)
            listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)
                .forEach { dialog.getButton(it)?.setTextColor(p.accent) }
        }
        return dialog
    }
}
