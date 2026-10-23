package com.lightplayer.ui.music

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.lightplayer.databinding.DialogTtsGenerateBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lightplayer.R
import com.lightplayer.databinding.FragmentMusicBinding
import com.lightplayer.tts.TtsIndex
import com.lightplayer.util.Formats
import android.widget.Toast

class MusicFragment : Fragment() {

    private var _binding: FragmentMusicBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: MusicTrackAdapter
    private val tracks = ArrayList<TtsTrack>()

    private var player: MediaPlayer? = null
    private var currentUri: Uri? = null

    private var converter: BatchTtsConverter? = null
    private var batchDialog: androidx.appcompat.app.AlertDialog? = null
    private var batchBinding: DialogTtsGenerateBinding? = null

    // Several subtitle files at once (the system picker allows multi-select).
    private val subtitlesPicker =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) startBatch(uris)
        }

    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            val p = player
            if (p != null && currentUri != null) {
                try {
                    val dur = p.duration.coerceAtLeast(1)
                    binding.npSeek.max = dur
                    binding.npSeek.progress = p.currentPosition
                    binding.npTime.text = "${Formats.time(p.currentPosition.toLong())} / ${Formats.time(dur.toLong())}"
                } catch (t: Throwable) {
                    // Player not in a valid state; next tick will settle it.
                }
            }
            ticker.postDelayed(this, 500)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMusicBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        adapter = MusicTrackAdapter(
            onTrackClick = { track -> toggle(track) },
            onTrackLongClick = { track -> confirmDelete(track) }
        )
        binding.recycler.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.btnConvert.setOnClickListener {
            if (converter == null) subtitlesPicker.launch(arrayOf("*/*"))
        }
        binding.npPlay.setOnClickListener { playPause() }
        binding.npStop.setOnClickListener { stopPlayback() }
        binding.npSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    try { player?.seekTo(progress) } catch (t: Throwable) {}
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        ticker.post(tick)
        loadTracks()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) loadTracks()
    }

    override fun onDestroyView() {
        converter?.cancel()
        converter = null
        dismissBatchDialog()
        ticker.removeCallbacks(tick)
        releasePlayer()
        binding.recycler.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun loadTracks() {
        val appCtx = requireContext().applicationContext
        Thread {
            val list = try {
                TtsTrackRepository.load(appCtx)
            } catch (t: Throwable) {
                emptyList<TtsTrack>()
            }
            activity?.runOnUiThread {
                if (_binding == null || !isAdded) return@runOnUiThread
                tracks.clear()
                tracks.addAll(list)
                adapter.submit(tracks)
                adapter.playingUri = currentUri
                binding.emptyState.visibility = if (tracks.isEmpty()) View.VISIBLE else View.GONE
            }
        }.start()
    }

    private fun toggle(track: TtsTrack) {
        if (currentUri == track.uri) {
            playPause()
            return
        }
        releasePlayer()
        val appCtx = requireContext().applicationContext

        val p = MediaPlayer()
        try {
            p.setDataSource(appCtx, track.uri)
        } catch (t: Throwable) {
            Toast.makeText(requireContext(), R.string.player_error_play, Toast.LENGTH_SHORT).show()
            return
        }
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        p.setOnPreparedListener { mp ->
            if (_binding == null) return@setOnPreparedListener
            mp.start()
            binding.nowPlaying.visibility = View.VISIBLE
            binding.npTitle.text = track.videoTitle
            binding.npPlay.setImageResource(R.drawable.ic_pause)
            binding.npSeek.max = mp.duration.coerceAtLeast(1)
            adapter.playingUri = track.uri
        }
        p.setOnCompletionListener {
            if (_binding != null) binding.npPlay.setImageResource(R.drawable.ic_play)
        }
        p.setOnErrorListener { _, _, _ ->
            stopPlayback()
            true
        }

        player = p
        currentUri = track.uri
        adapter.playingUri = track.uri
        p.prepareAsync()
    }

    private fun playPause() {
        val p = player ?: return
        try {
            if (p.isPlaying) {
                p.pause()
                binding.npPlay.setImageResource(R.drawable.ic_play)
            } else {
                p.start()
                binding.npPlay.setImageResource(R.drawable.ic_pause)
            }
        } catch (t: Throwable) {}
    }

    private fun stopPlayback() {
        releasePlayer()
        if (_binding != null) {
            binding.nowPlaying.visibility = View.GONE
            binding.npPlay.setImageResource(R.drawable.ic_play)
        }
    }

    private fun releasePlayer() {
        player?.let { p ->
            try { p.stop() } catch (t: Throwable) {}
            try { p.release() } catch (t: Throwable) {}
        }
        player = null
        currentUri = null
        adapter.playingUri = null
    }

    private fun confirmDelete(track: TtsTrack) {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.music_delete_title)
            .setMessage(getString(R.string.music_delete_msg, track.videoTitle))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                val wasPlaying = currentUri == track.uri
                if (wasPlaying) stopPlayback()
                val appCtx = ctx.applicationContext
                val uri = track.uri
                Thread {
                    try { appCtx.contentResolver.delete(uri, null, null) } catch (t: Throwable) {}
                    TtsIndex(appCtx).remove(uri.toString())
                    activity?.runOnUiThread {
                        if (_binding != null) loadTracks()
                    }
                }.start()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---------- batch subtitle -> audio ----------

    private fun startBatch(uris: List<Uri>) {
        if (converter != null || _binding == null) return
        val ctx = requireContext()

        val db = DialogTtsGenerateBinding.inflate(layoutInflater)
        batchBinding = db
        db.dlgProgress.isIndeterminate = true
        db.dlgTitle.text = getString(R.string.batch_title_fmt, 1, uris.size)
        db.dlgSub.setText(R.string.dlg_generating_preparing)
        db.dlgPercent.text = ""
        batchDialog = MaterialAlertDialogBuilder(ctx)
            .setView(db.root)
            .setCancelable(false)
            .create()
        batchDialog?.show()
        binding.root.keepScreenOn = true

        val conv = BatchTtsConverter(ctx, uris, object : BatchTtsConverter.Listener {
            override fun onFileStart(index: Int, total: Int, name: String) {
                val d = batchBinding ?: return
                d.dlgTitle.text = getString(R.string.batch_title_fmt, index + 1, total)
                d.dlgSub.text = name
                d.dlgProgress.isIndeterminate = true
                d.dlgPercent.text = ""
            }

            override fun onCueProgress(done: Int, total: Int, preparing: Boolean) {
                val d = batchBinding ?: return
                if (preparing || total <= 0) {
                    d.dlgProgress.isIndeterminate = true
                    d.dlgPercent.text = ""
                } else {
                    d.dlgProgress.isIndeterminate = false
                    d.dlgProgress.max = total
                    d.dlgProgress.progress = done
                    d.dlgPercent.text = "${done * 100 / total}%"
                }
            }

            override fun onFinished(results: List<BatchTtsConverter.Result>, canceled: Boolean) {
                converter = null
                dismissBatchDialog()
                if (_binding == null || !isAdded) return
                binding.root.keepScreenOn = false
                showBatchSummary(results, canceled)
                loadTracks()
            }
        })
        converter = conv
        db.dlgCancel.setOnClickListener { conv.cancel() }
        conv.start()
    }

    private fun dismissBatchDialog() {
        try { batchDialog?.dismiss() } catch (_: Throwable) {}
        batchDialog = null
        batchBinding = null
        if (_binding != null) binding.root.keepScreenOn = false
    }

    private fun showBatchSummary(results: List<BatchTtsConverter.Result>, canceled: Boolean) {
        val converted = results.count { it.status == BatchTtsConverter.Status.CONVERTED }
        val reused = results.count { it.status == BatchTtsConverter.Status.REUSED }
        val failed = results.filter { it.status == BatchTtsConverter.Status.FAILED }

        val lines = ArrayList<String>()
        lines.add(getString(R.string.batch_summary_new, converted))
        lines.add(getString(R.string.batch_summary_reused, reused))
        if (failed.isNotEmpty()) {
            lines.add(getString(R.string.batch_summary_failed, failed.size))
            failed.forEach { lines.add(getString(R.string.batch_failed_item, it.name, it.detail)) }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(if (canceled) R.string.batch_summary_stopped else R.string.batch_summary_done)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
