package com.lightplayer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.lightplayer.databinding.ActivityMainBinding
import com.lightplayer.ui.music.MusicFragment
import com.lightplayer.ui.settings.SettingsFragment
import com.lightplayer.ui.video.VideoFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val videoFragment by lazy { VideoFragment() }
    private val musicFragment by lazy { MusicFragment() }
    private val settingsFragment by lazy { SettingsFragment() }
    private var active: Fragment? = null
    private var permissionCallback: ((Boolean) -> Unit)? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val cb = permissionCallback
            permissionCallback = null
            cb?.invoke(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showFragment(videoFragment)
        active = videoFragment

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_video -> showFragment(videoFragment)
                R.id.nav_music -> showFragment(musicFragment)
                else -> showFragment(settingsFragment)
            }
            true
        }

        if (!hasVideoPermission(this)) {
            requestVideoPermission { }
        }
    }

    private fun showFragment(target: Fragment) {
        if (target === active && target.isAdded) return
        val tx = supportFragmentManager.beginTransaction()
        active?.let { tx.hide(it) }
        if (!target.isAdded) tx.add(R.id.container, target) else tx.show(target)
        tx.commit()
        active = target
    }

    fun requestVideoPermission(callback: (Boolean) -> Unit) {
        permissionCallback = callback
        permissionLauncher.launch(
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
            else Manifest.permission.READ_EXTERNAL_STORAGE
        )
    }

    companion object {
        fun hasVideoPermission(context: Context): Boolean {
            val permission =
                if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO
                else Manifest.permission.READ_EXTERNAL_STORAGE
            return ContextCompat.checkSelfPermission(context, permission) ==
                    PackageManager.PERMISSION_GRANTED
        }
    }
}