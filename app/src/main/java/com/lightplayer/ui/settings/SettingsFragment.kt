package com.lightplayer.ui.settings

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.lightplayer.MainActivity
import com.lightplayer.theme.Palette
import com.lightplayer.theme.ThemeManager
import com.lightplayer.R
import com.lightplayer.databinding.FragmentSettingsBinding
import com.lightplayer.tts.TtsIndex
import com.lightplayer.ui.music.TtsTrackRepository
import com.lightplayer.util.Formats
import com.lightplayer.util.Prefs
import java.io.File

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    private class Chip(val view: TextView, val key: String)

    private val modeChips = ArrayList<Chip>()
    private val colorChips = ArrayList<Chip>()
    private val navChips = ArrayList<Chip>()

    // Android 13+ asks before a notification can be shown; background audio uses one.
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()
        ThemeManager.apply(view, ThemeManager.palette(ctx))
        setupAppearance()
        setupPlayback()
        binding.btnGrantAccess.setOnClickListener {
            (activity as? MainActivity)?.requestVideoPermission { updateFileStatus() }
        }
        updateFileStatus()

        // --- TTS engine ---
        binding.engineValue.text = engineLabel(Prefs.engine(ctx))
        binding.engineRow.setOnClickListener { pickEngine() }

        // --- Rate / pitch (0.50x .. 2.00x) ---
        binding.rateSeek.progress = toProgress(Prefs.rate(ctx))
        binding.pitchSeek.progress = toProgress(Prefs.pitch(ctx))
        updateRateLabel()
        updatePitchLabel()

        binding.rateSeek.setOnSeekBarChangeListener(simpleSeek { progress ->
            Prefs.setRate(ctx, fromProgress(progress))
            updateRateLabel()
        })
        binding.pitchSeek.setOnSeekBarChangeListener(simpleSeek { progress ->
            Prefs.setPitch(ctx, fromProgress(progress))
            updatePitchLabel()
        })

        // --- Storage ---
        binding.clearTts.setOnClickListener { confirmClearTts() }
        binding.clearCache.setOnClickListener {
            Thread {
                try { File(requireContext().cacheDir, "tts").deleteRecursively() } catch (t: Throwable) {}
                activity?.runOnUiThread {
                    if (_binding != null) Toast.makeText(requireContext(), R.string.settings_cleared, Toast.LENGTH_SHORT).show()
                }
            }.start()
        }

        // --- About ---
        val version = try {
            requireContext().packageManager.getPackageInfo(requireContext().packageName, 0).versionName ?: "1.0"
        } catch (t: Throwable) { "1.0" }
        binding.versionText.text = getString(R.string.settings_version_fmt, version)

        refreshStorageInfo()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            refreshStorageInfo()
            updateFileStatus()
        }
    }

    // ---------- appearance ----------

    private fun setupAppearance() {
        val ctx = requireContext()
        val modes = listOf(
            ThemeManager.MODE_SYSTEM to R.string.mode_system,
            ThemeManager.MODE_LIGHT to R.string.mode_light,
            ThemeManager.MODE_DARK to R.string.mode_dark,
            ThemeManager.MODE_BLACK to R.string.mode_black
        )
        modes.forEach { (key, label) ->
            val chip = makeChip(getString(label), key) {
                ThemeManager.setMode(ctx, key)
                themeChanged(animate = true)
            }
            modeChips.add(chip)
            binding.modeChips.addView(chip.view, chipParams())
        }
        ThemeManager.presets.forEach { preset ->
            val chip = makeChip(preset.name, preset.name) {
                ThemeManager.setPreset(ctx, preset.name)
                binding.hueSeek.progress = preset.hue.toInt()
                themeChanged(animate = true)
            }
            colorChips.add(chip)
            binding.colorChips.addView(chip.view, chipParams())
        }

        listOf("pill" to R.string.nav_pill, "classic" to R.string.nav_classic).forEach { (key, label) ->
            val chip = makeChip(getString(label), key) {
                Prefs.setNavStyle(ctx, key)
                (activity as? MainActivity)?.applyNavStyle(animate = true)
                refreshChips(animate = true)
            }
            navChips.add(chip)
            binding.navChips.addView(chip.view, chipParams())
        }

        binding.hueSeek.progress = ThemeManager.hue(ctx).toInt().coerceIn(0, 360)
        binding.hueSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    ThemeManager.setCustomHue(ctx, progress.toFloat())
                    themeChanged(animate = false)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        refreshChips(animate = false)
    }

    private fun setupPlayback() {
        val ctx = requireContext()
        binding.switchResume.isChecked = Prefs.resumeEnabled(ctx)
        binding.switchAutoNext.isChecked = Prefs.autoNext(ctx)
        binding.switchBgAudio.isChecked = Prefs.backgroundAudio(ctx)

        binding.switchResume.setOnCheckedChangeListener { _, on -> Prefs.setResumeEnabled(ctx, on) }
        binding.switchAutoNext.setOnCheckedChangeListener { _, on -> Prefs.setAutoNext(ctx, on) }
        binding.switchBgAudio.setOnCheckedChangeListener { _, on ->
            Prefs.setBackgroundAudio(ctx, on)
            if (on && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun themeChanged(animate: Boolean) {
        (activity as? MainActivity)?.onThemeChanged(animate)
        refreshChips(animate = animate)
    }

    private fun chipParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { marginEnd = dpPx(8) }

    private fun dpPx(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun makeChip(label: String, key: String, onClick: () -> Unit): Chip {
        val tv = TextView(requireContext()).apply {
            text = label
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dpPx(16), dpPx(9), dpPx(16), dpPx(9))
            background = GradientDrawable().apply { cornerRadius = dpPx(40).toFloat() }
            isClickable = true
            isFocusable = true
            stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(context, R.animator.press_scale)
            setOnClickListener { onClick() }
        }
        return Chip(tv, key)
    }

    private fun refreshChips(animate: Boolean) {
        val ctx = requireContext()
        val p = ThemeManager.palette(ctx)
        val mode = ThemeManager.mode(ctx)
        val preset = ThemeManager.preset(ctx)
        modeChips.forEach { styleChip(it, it.key == mode, p, animate) }
        colorChips.forEach { styleChip(it, it.key == preset, p, animate) }
        val nav = Prefs.navStyle(ctx)
        navChips.forEach { styleChip(it, it.key == nav, p, animate) }
    }

    private fun styleChip(chip: Chip, selected: Boolean, p: Palette, animate: Boolean) {
        val bgTo = if (selected) p.accent else p.container
        val fgTo = if (selected) p.onAccent else p.onContainer
        val bg = chip.view.background as GradientDrawable
        if (!animate) {
            bg.setColor(bgTo)
            chip.view.setTextColor(fgTo)
            return
        }
        val bgFrom = bg.color?.defaultColor ?: bgTo
        ValueAnimator.ofObject(ArgbEvaluator(), bgFrom, bgTo).apply {
            duration = 250
            addUpdateListener {
                bg.setColor(it.animatedValue as Int)
            }
            start()
        }
        chip.view.setTextColor(fgTo)
    }

    private fun updateFileStatus() {
        if (_binding == null) return
        val ctx = context ?: return
        val full = MainActivity.hasVideoPermission(ctx) && !MainActivity.hasPartialVideoAccess(ctx)
        val partial = MainActivity.hasPartialVideoAccess(ctx)
        binding.fileStatus.setText(
            when {
                full -> R.string.settings_file_granted
                partial -> R.string.settings_file_limited
                else -> R.string.settings_file_denied
            }
        )
        binding.btnGrantAccess.visibility = if (full) View.GONE else View.VISIBLE
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ---------- helpers ----------

    private fun toProgress(value: Float): Int = (((value - 0.5f) / 1.5f) * 100f).toInt().coerceIn(0, 100)
    private fun fromProgress(progress: Int): Float = 0.5f + (progress / 100f) * 1.5f

    private fun updateRateLabel() {
        binding.rateValue.text = String.format(java.util.Locale.US, "%.2fx", Prefs.rate(requireContext()))
    }

    private fun updatePitchLabel() {
        binding.pitchValue.text = String.format(java.util.Locale.US, "%.2fx", Prefs.pitch(requireContext()))
    }

    private fun simpleSeek(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }

    private fun engineChoices(): List<Pair<String?, String>> {
        val ctx = requireContext()
        val pm = ctx.packageManager
        val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val list = ArrayList<Pair<String?, String>>()
        list.add(null to getString(R.string.settings_default_engine))
        try {
            pm.queryIntentServices(intent, PackageManager.GET_SERVICES)?.forEach { resolve ->
                val pkg = resolve.serviceInfo?.packageName ?: return@forEach
                if (list.none { it.first == pkg }) {
                    val label = try { resolve.loadLabel(pm).toString() } catch (t: Throwable) { pkg }
                    list.add(pkg to label)
                }
            }
        } catch (t: Throwable) {}
        return list
    }

    private fun engineLabel(pkg: String?): String {
        if (pkg.isNullOrBlank()) return getString(R.string.settings_default_engine)
        return engineChoices().firstOrNull { it.first == pkg }?.second ?: pkg
    }

    private fun pickEngine() {
        val choices = engineChoices()
        val labels = choices.map { it.second }.toTypedArray()
        val checked = choices.indexOfFirst { it.first == Prefs.engine(requireContext()) }.coerceAtLeast(0)
        ThemeManager.themed(
            ThemeManager.dialogBuilder(requireContext())
                .setTitle(R.string.settings_engine)
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    Prefs.setEngine(requireContext(), choices[which].first)
                    binding.engineValue.text = choices[which].second
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .create()
        ).show()
    }

    private fun refreshStorageInfo() {
        val appCtx = requireContext().applicationContext
        Thread {
            val tracks = try { TtsTrackRepository.load(appCtx) } catch (t: Throwable) { emptyList() }
            val total = tracks.sumOf { it.size }
            activity?.runOnUiThread {
                if (_binding == null || !isAdded) return@runOnUiThread
                binding.storageInfo.text = getString(R.string.settings_generated_fmt, tracks.size, Formats.size(total))
            }
        }.start()
    }

    private fun confirmClearTts() {
        val appCtx = requireContext().applicationContext
        ThemeManager.themed(ThemeManager.dialogBuilder(requireContext())
            .setTitle(R.string.settings_clear_tts)
            .setMessage(R.string.settings_clear_confirm)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                Thread {
                    val tracks = try { TtsTrackRepository.load(appCtx) } catch (t: Throwable) { emptyList() }
                    for (t in tracks) {
                        try { appCtx.contentResolver.delete(t.uri, null, null) } catch (e: Throwable) {}
                    }
                    TtsIndex(appCtx).clear()
                    activity?.runOnUiThread {
                        if (_binding == null) return@runOnUiThread
                        refreshStorageInfo()
                        Toast.makeText(requireContext(), R.string.settings_cleared, Toast.LENGTH_SHORT).show()
                    }
                }.start()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .create()).show()
    }
}
