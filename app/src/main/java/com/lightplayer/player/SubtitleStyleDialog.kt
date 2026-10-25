package com.lightplayer.player

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import com.lightplayer.R
import com.lightplayer.theme.ThemeManager
import com.lightplayer.util.Prefs
import com.lightplayer.util.SubtitleStyle

/** Dialog to choose subtitle size, color and background, with a live preview. */
object SubtitleStyleDialog {

    fun show(activity: Activity, forceDark: Boolean, onChanged: () -> Unit) {
        val p = ThemeManager.palette(activity, forceDark)
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }

        // Preview on a picture-like backdrop.
        val preview = TextView(activity).apply {
            text = activity.getString(R.string.sub_preview)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(6), dp(12), dp(6))
        }
        container.addView(
            FrameLayout(activity).apply {
                background = ThemeManager.rounded(activity, 0xFF39414D.toInt(), 14f)
                minimumHeight = dp(84)
                addView(
                    preview,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
                    )
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84))
        )

        fun refresh() {
            SubtitleStyle.apply(activity, preview)
            onChanged()
        }

        fun label(textRes: Int) = TextView(activity).apply {
            setText(textRes)
            setTextColor(p.text2)
            textSize = 13f
            setPadding(0, dp(16), 0, dp(6))
        }

        // ---- size ----
        container.addView(label(R.string.sub_size))
        val size = SeekBar(activity).apply {
            max = SubtitleStyle.MAX_SIZE - SubtitleStyle.MIN_SIZE
            progress = (Prefs.subtitleSize(activity) - SubtitleStyle.MIN_SIZE).toInt().coerceIn(0, max)
            progressTintList = ColorStateList.valueOf(p.accent)
            thumbTintList = ColorStateList.valueOf(p.accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    Prefs.setSubtitleSize(activity, (SubtitleStyle.MIN_SIZE + value).toFloat())
                    refresh()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        container.addView(size)

        // ---- color ----
        container.addView(label(R.string.sub_color))
        val swatches = ArrayList<Pair<View, Int>>()
        fun styleSwatches() {
            val current = Prefs.subtitleColor(activity)
            swatches.forEach { (view, color) ->
                view.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(dp(if (color == current) 3 else 1), if (color == current) p.accent else 0x55FFFFFF)
                }
            }
        }
        val colorRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        SubtitleStyle.colors.forEach { color ->
            val swatch = View(activity).apply {
                setOnClickListener {
                    Prefs.setSubtitleColor(activity, color)
                    styleSwatches()
                    refresh()
                }
            }
            swatches.add(swatch to color)
            colorRow.addView(
                swatch,
                LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) }
            )
        }
        container.addView(colorRow)
        styleSwatches()

        // ---- background ----
        container.addView(label(R.string.sub_background))
        val chips = ArrayList<Pair<TextView, Int>>()
        fun styleChips() {
            val current = Prefs.subtitleBackground(activity)
            chips.forEach { (chip, mode) ->
                val on = mode == current
                chip.background = ThemeManager.rounded(activity, if (on) p.accent else p.container, 40f)
                chip.setTextColor(if (on) p.onAccent else p.onContainer)
            }
        }
        val bgRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            SubtitleStyle.BG_NONE to R.string.sub_bg_none,
            SubtitleStyle.BG_DIM to R.string.sub_bg_dim,
            SubtitleStyle.BG_SOLID to R.string.sub_bg_solid
        ).forEach { (mode, labelRes) ->
            val chip = TextView(activity).apply {
                setText(labelRes)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(16), dp(9), dp(16), dp(9))
                setOnClickListener {
                    Prefs.setSubtitleBackground(activity, mode)
                    styleChips()
                    refresh()
                }
            }
            chips.add(chip to mode)
            bgRow.addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            )
        }
        container.addView(bgRow)
        styleChips()

        SubtitleStyle.apply(activity, preview)

        ThemeManager.themed(
            ThemeManager.dialogBuilder(activity, forceDark)
                .setTitle(R.string.sub_style_title)
                .setView(container)
                .setPositiveButton(R.string.action_done, null)
                .create(),
            forceDark
        ).show()
    }
}
