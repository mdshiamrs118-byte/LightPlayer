package com.lightplayer.theme

import android.animation.AnimatorInflater
import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.lightplayer.R

/**
 * Floating pill navigation bar. The selected tab grows into a capsule that shows its label;
 * switching tabs animates the capsule and the pill's width.
 */
class PillNavBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private class Item(
        val root: LinearLayout,
        val capsule: GradientDrawable,
        val icon: ImageView,
        val label: TextView
    ) {
        var alphaAnim: ValueAnimator? = null
    }

    private val items = ArrayList<Item>()
    private var selected = -1
    private var palette: Palette? = null

    var onSelect: ((Int) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = dp(8f)
        setPadding(pad, pad, pad, pad)
        elevation = dp(8f).toFloat()
        clipChildren = false
        clipToPadding = false
    }

    fun setItems(entries: List<Pair<Int, String>>) {
        removeAllViews()
        items.clear()
        entries.forEachIndexed { index, entry ->
            val iconRes = entry.first
            val title = entry.second

            // The capsule is the tab's own background (not a child view), so it can never
            // inflate the tab's measured width.
            val capsule = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24f).toFloat()
                alpha = 0
            }
            val icon = ImageView(context).apply {
                setImageResource(iconRes)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val label = TextView(context).apply {
                text = title
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                visibility = View.GONE
            }
            val root = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
                minimumWidth = dp(48f)
                setPadding(dp(12f), 0, dp(12f), 0)
                background = capsule
                isClickable = true
                isFocusable = true
                contentDescription = title
                stateListAnimator = AnimatorInflater.loadStateListAnimator(context, R.animator.press_scale)
                addView(icon, LayoutParams(dp(24f), dp(24f)))
                addView(
                    label,
                    LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginStart = dp(8f) }
                )
                setOnClickListener { select(index, animate = true, notify = true) }
            }
            addView(
                root,
                LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48f)).apply {
                    marginStart = dp(2f)
                    marginEnd = dp(2f)
                }
            )
            items.add(Item(root, capsule, icon, label))
        }
        selected = -1
        palette?.let { applyPalette(it) }
    }

    fun select(index: Int, animate: Boolean, notify: Boolean = false) {
        if (index == selected || index !in items.indices) return
        val previous = selected
        selected = index

        if (animate) {
            // Runs on the parent so the pill itself can change width.
            val scene = (parent as? ViewGroup) ?: this
            TransitionManager.beginDelayedTransition(
                scene,
                TransitionSet().apply {
                    addTransition(ChangeBounds())
                    addTransition(Fade())
                    duration = 280
                    interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
                }
            )
        }
        items.forEachIndexed { i, item ->
            val on = i == index
            item.label.visibility = if (on) View.VISIBLE else View.GONE
            setCapsule(item, on, animate)
        }
        palette?.let { applyColors(it) }

        if (animate) {
            items[index].icon.apply {
                scaleX = 0.7f
                scaleY = 0.7f
                animate().scaleX(1f).scaleY(1f).setDuration(320)
                    .setInterpolator(OvershootInterpolator(2.2f)).start()
            }
        }
        if (notify && previous != index) onSelect?.invoke(index)
    }

    private fun setCapsule(item: Item, on: Boolean, animate: Boolean) {
        val target = if (on) 255 else 0
        item.alphaAnim?.cancel()
        if (!animate) {
            item.capsule.alpha = target
            return
        }
        item.alphaAnim = ValueAnimator.ofInt(item.capsule.alpha, target).apply {
            duration = 260
            addUpdateListener { item.capsule.alpha = it.animatedValue as Int }
            start()
        }
    }

    fun applyPalette(p: Palette) {
        palette = p
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(32f).toFloat()
            setColor(p.accent)
        }
        applyColors(p)
    }

    private fun applyColors(p: Palette) {
        items.forEachIndexed { i, item ->
            val on = i == selected
            item.capsule.setColor(p.navCapsule)
            item.icon.imageTintList = ColorStateList.valueOf(if (on) p.accent else p.onAccent)
            item.label.setTextColor(p.accent)
        }
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
