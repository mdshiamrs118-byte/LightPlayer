package com.lightplayer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.lightplayer.databinding.ActivityMainBinding
import com.lightplayer.theme.ThemeManager
import com.lightplayer.ui.music.MusicFragment
import com.lightplayer.ui.settings.SettingsFragment
import com.lightplayer.ui.video.VideoFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val tags = arrayOf("video", "music", "settings")
    private var selectedIndex = 0
    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val cb = permissionCallback
            permissionCallback = null
            cb?.invoke(result.values.any { it } || hasVideoPermission(this))
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val palette = ThemeManager.palette(this)
        ThemeManager.applyWindow(this, palette)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ThemeManager.apply(binding.mainRoot, palette)

        selectedIndex = savedInstanceState?.getInt(KEY_TAB, 0) ?: 0

        binding.pillNav.setItems(
            listOf(
                R.drawable.ic_video to getString(R.string.tab_video),
                R.drawable.ic_music to getString(R.string.tab_music),
                R.drawable.ic_settings to getString(R.string.tab_settings)
            )
        )
        binding.pillNav.applyPalette(palette)
        binding.pillNav.onSelect = { index -> showTab(index, animate = true) }
        binding.pillNav.select(selectedIndex, animate = false)
        showTab(selectedIndex, animate = false)

        if (!hasVideoPermission(this)) {
            requestVideoPermission { }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, selectedIndex)
    }

    /** Day/night flipped while the app is open ("System" mode): recolor everything. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        ThemeManager.bump()
        onThemeChanged(animate = true)
    }

    private fun createFragment(index: Int): Fragment = when (index) {
        0 -> VideoFragment()
        1 -> MusicFragment()
        else -> SettingsFragment()
    }

    private fun showTab(index: Int, animate: Boolean) {
        val fm = supportFragmentManager
        val tx = fm.beginTransaction().setReorderingAllowed(true)
        if (animate) tx.setCustomAnimations(R.anim.screen_enter, R.anim.screen_exit)
        for (i in tags.indices) {
            val f = fm.findFragmentByTag(tags[i])
            if (f != null && i != index && !f.isHidden) tx.hide(f)
        }
        val target = fm.findFragmentByTag(tags[index])
        if (target == null) tx.add(R.id.container, createFragment(index), tags[index])
        else tx.show(target)
        tx.commit()
        selectedIndex = index
    }

    /**
     * Applies the current theme to the whole app. With [animate], a snapshot of the old look
     * fades away over the new one.
     */
    fun onThemeChanged(animate: Boolean) {
        val palette = ThemeManager.palette(this)
        val root = binding.mainRoot

        var overlay: ImageView? = null
        if (animate && root.width > 0 && root.height > 0) {
            try {
                val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(bmp))
                overlay = ImageView(this).apply {
                    setImageBitmap(bmp)
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }
                root.addView(overlay)
            } catch (_: Throwable) {
                overlay = null
            }
        }

        ThemeManager.applyWindow(this, palette)
        ThemeManager.applyTree(root, palette)
        binding.pillNav.applyPalette(palette)

        overlay?.let { o ->
            o.animate().alpha(0f).setDuration(380).withEndAction { root.removeView(o) }.start()
        }
    }

    fun requestVideoPermission(callback: (Boolean) -> Unit) {
        permissionCallback = callback
        permissionLauncher.launch(
            when {
                Build.VERSION.SDK_INT >= 34 -> arrayOf(
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
                Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
                else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        )
    }

    companion object {
        private const val KEY_TAB = "selected_tab"

        private fun granted(context: Context, permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        /** Full access, or (Android 14+) access to the videos the user picked. */
        fun hasVideoPermission(context: Context): Boolean {
            val full = if (Build.VERSION.SDK_INT >= 33) granted(context, Manifest.permission.READ_MEDIA_VIDEO)
            else granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            return full || hasPartialVideoAccess(context)
        }

        /** Android 14 "Select photos and videos" mode: only the picked items are visible. */
        fun hasPartialVideoAccess(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 34 &&
                !granted(context, Manifest.permission.READ_MEDIA_VIDEO) &&
                granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }
}
