package com.lightplayer.player

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import com.lightplayer.R
import com.lightplayer.util.Prefs
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The floating video window drawn over other apps ("Display over other apps").
 *
 *  - drag with one finger to move it
 *  - pinch, or drag the corner handle, to resize it (it always keeps the video's shape)
 *  - single tap shows / hides the buttons: expand, close, -10 and +10
 *  - double tap plays / pauses
 *
 * It only draws and handles touches; the owning service runs the player.
 * All methods must be called on the main thread.
 */
class PipOverlay(
    private val ctx: Context,
    private val callbacks: Callbacks
) {

    interface Callbacks {
        fun onTogglePlay()
        fun onSeekBy(deltaMs: Long)
        fun onClose()
        fun onExpand()
    }

    /** The video is drawn into this. */
    val texture = TextureView(ctx)

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = ctx.resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop

    private val root = FrameLayout(ctx)
    private val progress = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal)
    private val controls = FrameLayout(ctx)
    private val hud = ImageView(ctx)
    private val resizeHandle = ImageView(ctx)
    private lateinit var params: WindowManager.LayoutParams

    private var aspect = 16f / 9f
    private var attached = false
    private var controlsVisible = false

    // drag / pinch bookkeeping
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private var multiTouch = false
    private var lastGestureEnd = 0L

    // corner-handle resize bookkeeping
    private var handleDownX = 0f
    private var handleDownY = 0f
    private var handleStartWidth = 0

    private val hideControlsRunnable = Runnable { hideControls() }
    private val hideHudRunnable = Runnable {
        hud.animate().alpha(0f).setDuration(200).withEndAction { hud.visibility = View.GONE }.start()
    }

    private fun dp(v: Float): Int = (v * density + 0.5f).toInt()

    init {
        // Slightly rounded window with a faint outline so it stands out on dark apps.
        root.background = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = dp(16f).toFloat()
            setStroke(dp(1f), 0x33FFFFFF)
        }
        root.clipToOutline = true

        root.addView(texture, FrameLayout.LayoutParams(MATCH, MATCH))

        // thin progress line along the bottom edge
        progress.max = 1000
        progress.progressTintList = ColorStateList.valueOf(Color.WHITE)
        progress.progressBackgroundTintList = ColorStateList.valueOf(0x33FFFFFF)
        root.addView(progress, FrameLayout.LayoutParams(MATCH, dp(3f), Gravity.BOTTOM))

        // play / pause flash for double tap
        hud.apply {
            background = ovalBackground()
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            visibility = View.GONE
        }
        root.addView(hud, FrameLayout.LayoutParams(dp(56f), dp(56f), Gravity.CENTER))

        buildControls()
        installTouchHandling()
    }

    // ---------- building ----------

    private fun ovalBackground() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(0x77000000)
    }

    private fun iconButton(iconRes: Int, description: String, onClick: () -> Unit) = ImageView(ctx).apply {
        setImageResource(iconRes)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
        background = ovalBackground()
        contentDescription = description
        isClickable = true
        setOnClickListener {
            onClick()
            scheduleHideControls()
        }
    }

    private fun textButton(label: String, description: String, onClick: () -> Unit) = TextView(ctx).apply {
        text = label
        setTextColor(Color.WHITE)
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = ovalBackground()
        contentDescription = description
        isClickable = true
        setOnClickListener {
            onClick()
            scheduleHideControls()
        }
    }

    private fun buildControls() {
        controls.setBackgroundColor(0x55000000)
        controls.visibility = View.GONE
        controls.alpha = 0f

        // back to the full player
        controls.addView(
            iconButton(R.drawable.ic_pip_expand, ctx.getString(R.string.pip_expand)) { callbacks.onExpand() },
            FrameLayout.LayoutParams(dp(34f), dp(34f), Gravity.TOP or Gravity.START).apply {
                setMargins(dp(6f), dp(6f), 0, 0)
            }
        )
        // close
        controls.addView(
            iconButton(R.drawable.ic_notif_close, ctx.getString(R.string.pip_close)) { callbacks.onClose() },
            FrameLayout.LayoutParams(dp(34f), dp(34f), Gravity.TOP or Gravity.END).apply {
                setMargins(0, dp(6f), dp(6f), 0)
            }
        )
        // -10 / +10
        controls.addView(
            textButton("\u221210", ctx.getString(R.string.cd_rewind)) { callbacks.onSeekBy(-SEEK_MS) },
            FrameLayout.LayoutParams(dp(44f), dp(44f), Gravity.CENTER_VERTICAL or Gravity.START).apply {
                marginStart = dp(16f)
            }
        )
        controls.addView(
            textButton("+10", ctx.getString(R.string.cd_forward)) { callbacks.onSeekBy(SEEK_MS) },
            FrameLayout.LayoutParams(dp(44f), dp(44f), Gravity.CENTER_VERTICAL or Gravity.END).apply {
                marginEnd = dp(16f)
            }
        )

        // corner grip to resize
        resizeHandle.apply {
            setImageResource(R.drawable.ic_pip_resize)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(9f), dp(9f), dp(5f), dp(5f))
            alpha = 0.9f
            isClickable = true
            contentDescription = ctx.getString(R.string.cd_pip_resize)
        }
        controls.addView(
            resizeHandle,
            FrameLayout.LayoutParams(dp(38f), dp(38f), Gravity.BOTTOM or Gravity.END)
        )
        installResizeHandle()

        root.addView(controls, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ---------- touch: move, pinch, tap, double tap ----------

    private fun installTouchHandling() {
        val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                resizeAroundCenter((params.width * detector.scaleFactor).toInt())
                return true
            }
        })

        val tapDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (recentlyMoved()) return true
                toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (recentlyMoved()) return true
                callbacks.onTogglePlay()
                return true
            }
        })

        root.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            tapDetector.onTouchEvent(ev)

            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = ev.rawX
                    downRawY = ev.rawY
                    startX = params.x
                    startY = params.y
                    dragging = false
                    multiTouch = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    multiTouch = true
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!multiTouch && !scaleDetector.isInProgress && ev.pointerCount == 1) {
                        val dx = ev.rawX - downRawX
                        val dy = ev.rawY - downRawY
                        if (!dragging && hypot(dx, dy) > touchSlop) dragging = true
                        if (dragging) {
                            params.x = startX + dx.toInt()
                            params.y = startY + dy.toInt()
                            clampPosition()
                            update()
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging || multiTouch) {
                        lastGestureEnd = SystemClock.uptimeMillis()
                        saveGeometry()
                    }
                    dragging = false
                    multiTouch = false
                }
            }
            true
        }
    }

    /**
     * While dragging, the window moves with the finger, so the tap detector sees a finger that
     * "didn't move" and would report a tap on release. Ignore taps that follow a drag or pinch.
     */
    private fun recentlyMoved(): Boolean =
        dragging || multiTouch || SystemClock.uptimeMillis() - lastGestureEnd < 500

    private fun installResizeHandle() {
        resizeHandle.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.parent.requestDisallowInterceptTouchEvent(true)
                    handleDownX = ev.rawX
                    handleDownY = ev.rawY
                    handleStartWidth = params.width
                    handler.removeCallbacks(hideControlsRunnable)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // A diagonal drag feels natural; the top-left corner stays where it is.
                    val dx = ev.rawX - handleDownX
                    val dy = ev.rawY - handleDownY
                    applySize(handleStartWidth + ((dx + dy * aspect) / 2f).toInt())
                    clampPosition()
                    update()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    saveGeometry()
                    scheduleHideControls()
                    true
                }
                else -> false
            }
        }
    }

    // ---------- showing and hiding the buttons ----------

    fun toggleControls() {
        if (controlsVisible) hideControls() else showControls()
    }

    fun showControls() {
        controlsVisible = true
        controls.animate().cancel()
        if (controls.visibility != View.VISIBLE) {
            controls.alpha = 0f
            controls.visibility = View.VISIBLE
        }
        controls.animate().alpha(1f).setDuration(160).start()
        scheduleHideControls()
    }

    fun hideControls() {
        controlsVisible = false
        handler.removeCallbacks(hideControlsRunnable)
        controls.animate().cancel()
        controls.animate().alpha(0f).setDuration(160).withEndAction {
            if (!controlsVisible) controls.visibility = View.GONE
        }.start()
    }

    private fun scheduleHideControls() {
        handler.removeCallbacks(hideControlsRunnable)
        handler.postDelayed(hideControlsRunnable, CONTROLS_TIMEOUT_MS)
    }

    /** Shows the new state (play or pause icon) in the middle of the window, then fades it. */
    fun flashPlayState(playing: Boolean) {
        hud.setImageResource(if (playing) R.drawable.ic_notif_play else R.drawable.ic_notif_pause)
        hud.animate().cancel()
        handler.removeCallbacks(hideHudRunnable)
        hud.visibility = View.VISIBLE
        hud.alpha = 1f
        hud.scaleX = 0.6f
        hud.scaleY = 0.6f
        hud.animate().scaleX(1f).scaleY(1f).setDuration(220)
            .setInterpolator(OvershootInterpolator(2f)).start()
        handler.postDelayed(hideHudRunnable, 520)
    }

    fun setProgress(fraction: Float) {
        progress.progress = (fraction.coerceIn(0f, 1f) * 1000).toInt()
    }

    // ---------- window size and position ----------

    private fun screenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Pair(b.width(), b.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun clampWidth(w: Int): Int {
        val (sw, sh) = screenSize()
        val min = dp(140f)
        val max = maxOf(min, minOf((sw * 0.92f).toInt(), (sh * 0.72f * aspect).toInt()))
        return w.coerceIn(min, max)
    }

    /** Sets the width and derives the height from the video's shape. */
    private fun applySize(widthPx: Int) {
        val w = clampWidth(widthPx)
        params.width = w
        params.height = (w / aspect).toInt()
    }

    private fun resizeAroundCenter(newWidth: Int) {
        val oldW = params.width
        val oldH = params.height
        applySize(newWidth)
        params.x += (oldW - params.width) / 2
        params.y += (oldH - params.height) / 2
        clampPosition()
        update()
    }

    fun clampPosition() {
        if (!::params.isInitialized) return
        val (sw, sh) = screenSize()
        params.x = params.x.coerceIn(0, maxOf(0, sw - params.width))
        params.y = params.y.coerceIn(0, maxOf(0, sh - params.height))
    }

    /** Called when the video's real shape becomes known (or changes). */
    fun setAspect(value: Float) {
        if (value < 0.2f || abs(value - aspect) < 0.002f) return
        aspect = value
        if (attached) {
            applySize(params.width)
            clampPosition()
            update()
        }
    }

    private fun update() {
        if (!attached) return
        try {
            wm.updateViewLayout(root, params)
        } catch (_: Throwable) {
        }
    }

    private fun saveGeometry() {
        if (!::params.isInitialized) return
        Prefs.setPipGeometry(ctx, params.width, params.x, params.y)
    }

    // ---------- attach / detach ----------

    /** Adds the window to the screen. Throws if the overlay permission is missing. */
    fun attach(initialAspect: Float) {
        if (attached) return
        if (initialAspect >= 0.2f) aspect = initialAspect

        val (sw, sh) = screenSize()
        val saved = Prefs.pipWidth(ctx)
        val width = clampWidth(if (saved > 0) saved else (minOf(sw, sh) * 0.62f).toInt())

        params = WindowManager.LayoutParams(
            width,
            (width / aspect).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val sx = Prefs.pipX(ctx)
            val sy = Prefs.pipY(ctx)
            x = if (sx >= 0) sx else sw - width - dp(16f)
            y = if (sy >= 0) sy else dp(96f)
        }
        clampPosition()

        wm.addView(root, params)
        attached = true

        root.alpha = 0f
        root.scaleX = 0.88f
        root.scaleY = 0.88f
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
            .setInterpolator(DecelerateInterpolator(1.6f)).start()

        // Show the buttons briefly so it is obvious what the window can do.
        showControls()
    }

    /** Removes the window; [onDone] runs once it is gone. */
    fun detach(animate: Boolean, onDone: () -> Unit) {
        handler.removeCallbacksAndMessages(null)
        if (!attached) {
            onDone()
            return
        }
        val remove = {
            try {
                wm.removeView(root)
            } catch (_: Throwable) {
            }
            attached = false
            onDone()
        }
        root.animate().cancel()
        if (animate) {
            root.animate().alpha(0f).scaleX(0.88f).scaleY(0.88f).setDuration(170)
                .withEndAction { remove() }.start()
        } else {
            remove()
        }
    }

    companion object {
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val SEEK_MS = 10_000L
        private const val CONTROLS_TIMEOUT_MS = 3_500L
    }
}
