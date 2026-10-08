package com.lightplayer.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val ctx = requireContext()

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
        if (_binding != null) refreshStorageInfo()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ---------- helpers ----------

    private fun toProgress(value: Float): Int = (((value - 0.5f) / 1.5f) * 100f).toInt().coerceIn(0, 100)
    private fun fromProgress(progress: Int): Float = 0.5f + (progress / 100f) * 1.5f

    private fun updateRateLabel() {
        binding.rateValue.text = getString(R.string.settings_value_fmt_placeholder)
            .replace("%1", String.format(java.util.Locale.US, "%.2f", Prefs.rate(requireContext())))
            .let { String.format(java.util.Locale.US, "%.2fx", Prefs.rate(requireContext())) }
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
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_engine)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                Prefs.setEngine(requireContext(), choices[which].first)
                binding.engineValue.text = choices[which].second
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
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
        MaterialAlertDialogBuilder(requireContext())
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
            .show()
    }
}