package com.lightplayer.player

import android.app.Activity
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.lightplayer.R
import com.lightplayer.theme.ThemeManager
import java.util.Locale

/** Nudge subtitles (and the spoken track) earlier or later while the video keeps playing. */
object SubtitleSyncDialog {

    fun label(ms: Long): String = String.format(Locale.US, "%+.1f s", ms / 1000.0)

    fun show(activity: Activity, forceDark: Boolean, current: Long, onChange: (Long) -> Unit) {
        val p = ThemeManager.palette(activity, forceDark)
        fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()

        var offset = current
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(8), dp(24), dp(4))
        }

        val value = TextView(activity).apply {
            text = label(offset)
            setTextColor(p.text1)
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        container.addView(value)
        container.addView(TextView(activity).apply {
            setText(R.string.sync_hint)
            setTextColor(p.text2)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(14))
        })

        fun change(delta: Long) {
            offset = (offset + delta).coerceIn(-60_000L, 60_000L)
            value.text = label(offset)
            onChange(offset)
        }

        fun chip(text: String, onClick: () -> Unit) = TextView(activity).apply {
            this.text = text
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(p.onContainer)
            background = ThemeManager.rounded(activity, p.container, 40f)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnClickListener { onClick() }
        }

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        fun add(c: TextView) = row.addView(
            c,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(4); marginEnd = dp(4) }
        )
        add(chip("\u22120.5") { change(-500) })
        add(chip("\u22120.1") { change(-100) })
        add(chip("+0.1") { change(100) })
        add(chip("+0.5") { change(500) })
        container.addView(row)

        container.addView(
            chip(activity.getString(R.string.sync_reset)) {
                offset = 0L
                value.text = label(0L)
                onChange(0L)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14) }
        )

        ThemeManager.themed(
            ThemeManager.dialogBuilder(activity, forceDark)
                .setTitle(R.string.sync_title)
                .setView(container)
                .setPositiveButton(R.string.action_done, null)
                .create(),
            forceDark
        ).show()
    }
}
