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
import androidx.fragment.app.Fragment
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
}