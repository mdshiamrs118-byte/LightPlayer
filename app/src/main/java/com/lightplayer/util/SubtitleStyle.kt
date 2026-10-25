package com.lightplayer.util

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.widget.TextView

/** How subtitles look: size, text color and background. Applied to any TextView. */
object SubtitleStyle {

    val colors = intArrayOf(
        0xFFFFFFFF.toInt(),   // white
        0xFFFFEB3B.toInt(),   // yellow
        0xFF7DD3FC.toInt(),   // sky blue
        0xFF86EFAC.toInt(),   // green
        0xFFFCA5A5.toInt()    // pink
    )

    const val MIN_SIZE = 12
    const val MAX_SIZE = 32

    const val BG_NONE = 0
    const val BG_DIM = 1
    const val BG_SOLID = 2

    fun apply(ctx: Context, tv: TextView) {
        tv.textSize = Prefs.subtitleSize(ctx)
        tv.setTextColor(Prefs.subtitleColor(ctx))
        val density = ctx.resources.displayMetrics.density
        when (Prefs.subtitleBackground(ctx)) {
            BG_NONE -> {
                tv.background = null
                // No box, so give the letters a dark halo to stay readable on any picture.
                tv.setShadowLayer(6f * density, 0f, 0f, Color.BLACK)
            }
            BG_SOLID -> {
                tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                tv.background = box(0xFF000000.toInt(), density)
            }
            else -> {
                tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                tv.background = box(0x99000000.toInt(), density)
            }
        }
    }

    private fun box(color: Int, density: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 8f * density
    }
}
