package com.lightplayer.player

import android.content.pm.PackageManager
import android.graphics.Matrix
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.lightplayer.R
import com.lightplayer.databinding.ActivityPlayerBinding
import com.lightplayer.databinding.DialogTtsGenerateBinding
import com.lightplayer.subtitle.Cue
import com.lightplayer.subtitle.SubtitleParser
import com.lightplayer.subtitle.SubtitleTrack
import com.lightplayer.tts.TtsGenerator
import com.lightplayer.tts.TtsIndex
import com.lightplayer.util.Formats
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_DURATION = "extra_duration"
        private const val MAX_SUBTITLE_BYTES = 8L * 1024 * 1024
    }

    private lateinit var b: ActivityPlayerBinding

    private var videoUri: Uri? = null
    private var videoTitle = ""
    private var declaredDuration = 0

    private val mediaPlayer = MediaPlayer()
    private var prepared = false
    private var surface: Surface? = null
    private var videoW = 0
    private var videoH = 0
    private var userDragging = false
    private var resumeAfterWindow = false

    private var ttsPlayer: MediaPlayer? = null
    private var ttsReady = false
    private var ttsEnabled = false
    private var lastTtsSeekAt = 0L

    private var track: SubtitleTrack? = null
    private var lastShownCue: Cue? = null

    private lateinit var generator: TtsGenerator
    private var progressDialog: androidx.appcompat.app.AlertDialog? = null
    private var dialogBinding: DialogTtsGenerateBinding? = null
    private var generating = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private val hideControlsRunnable = Runnable { hideControls() }

    private val ticker = object : Runnable {
        override fun run() {
            if (prepared) {
                val pos = mediaPlayer.currentPosition
                if (!userDragging) {
                    b.seekBar.progress = pos
                    b.timeElapsed.text = Formats.time(pos.toLong())
                    val dur = mediaPlayer.duration
                    if (dur > 0) b.timeRemaining.text = "-" + Formats.time((dur - pos).toLong())
                }
                updateSubtitle(pos)
                syncTts(pos)
            }
            mainHandler.postDelayed(this, 250)
        }
    }

    private val subtitlePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onSubtitlePicked(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)

        videoUri = intent.data
        if (videoUri == null) { finish(); return }
        videoTitle = intent.getStringExtra(EXTRA_TITLE)
            ?: queryDisplayName(videoUri!!) ?: "Video"
        declaredDuration = intent.getLongExtra(EXTRA_DURATION, 0L).toInt()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()

        b.videoTitle.text = videoTitle
        b.buffering.visibility = View.GONE
        b.ttsChip.alpha = 0.4f

        generator = TtsGenerator(this)

        setupMediaPlayer()
        setupControls()
        setupSurface()

        b.videoSurface.setOnClickListener {
            if (controlsVisible) hideControls() else showControls()
        }

        showControls()
        mainHandler.post(ticker)
    }

    // ---------- media player ----------

    private fun setupMediaPlayer() {
        mediaPlayer.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        )
        mediaPlayer.setVolume(b.videoVol.progress / 100f, b.videoVol.progress / 100f)

        try {
            mediaPlayer.setDataSource(this, videoUri!!)
        } catch (t: Throwable) {
            Toast.makeText(this, R.string.player_error_play, Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        mediaPlayer.setOnPreparedListener { mp ->
            prepared = true
            b.buffering.visibility = View.GONE
            val dur = mp.duration
            if (dur > 0) b.seekBar.max = dur
            mp.start()
            b.btnPlay.setImageResource(R.drawable.ic_pause)
            scheduleHideControls()
        }
        mediaPlayer.setOnVideoSizeChangedListener { _, w, h ->
            if (w > 0 && h > 0) {
                videoW = w
                videoH = h
                updateVideoTransform()
            }
        }
        mediaPlayer.setOnInfoListener { _, what, _ ->
            when (what) {
                MediaPlayer.MEDIA_INFO_BUFFERING_START -> b.buffering.visibility = View.VISIBLE
                MediaPlayer.MEDIA_INFO_BUFFERING_END -> b.buffering.visibility = View.GONE
            }
            true
        }
        mediaPlayer.setOnCompletionListener {
            b.btnPlay.setImageResource(R.drawable.ic_play)
            showControls()
        }
        mediaPlayer.setOnErrorListener { _, _, _ ->
            b.buffering.visibility = View.GONE
            Toast.makeText(this, R.string.player_error_play, Toast.LENGTH_SHORT).show()
            finish()
            true
        }
        b.buffering.visibility = View.VISIBLE
        mediaPlayer.prepareAsync()
    }

    private fun setupSurface() {
        val listener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                attachSurface(st)
            }
            override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {
                updateVideoTransform()
            }
            override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
                mediaPlayer.setSurface(null)
                surface?.release()
                surface = null
                return true
            }
            override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
        }
        b.videoSurface.surfaceTextureListener = listener
        b.videoSurface.surfaceTexture?.let { attachSurface(it) }
    }

    private fun attachSurface(st: android.graphics.SurfaceTexture) {
        surface?.release()
        surface = Surface(st)
        mediaPlayer.setSurface(surface)
        updateVideoTransform()
    }

    private fun updateVideoTransform() {
        if (videoW <= 0 || videoH <= 0) return
        val vw = b.videoSurface.width.toFloat()
        val vh = b.videoSurface.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val scale = minOf(vw / videoW, vh / videoH)
        val matrix = Matrix()
        matrix.setScale(videoW * scale / vw, videoH * scale / vh, vw / 2f, vh / 2f)
        b.videoSurface.setTransform(matrix)
    }

    // ---------- controls ----------

    private fun setupControls() {
        b.btnPlay.setOnClickListener { togglePlay() }
        b.btnBack.setOnClickListener { finish() }
        b.btnClose.setOnClickListener { finish() }
        b.btnSubs.setOnClickListener { pickSubtitle() }
        b.ttsChip.setOnClickListener {
            if (ttsReady) toggleTts() else pickSubtitle()
        }

        b.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    b.timeElapsed.text = Formats.time(progress.toLong())
                    b.timeRemaining.text = "-" + Formats.time((sb.max - progress).toLong())
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {
                userDragging = true
                showControls()
            }
            override fun onStopTrackingTouch(sb: SeekBar) {
                userDragging = false
                if (prepared) {
                    mediaPlayer.seekTo(sb.progress)
                    forceTtsTo(sb.progress.toLong())
                }
            }
        })

        b.videoVol.setOnSeekBarChangeListener(volumeListener { p ->
            mediaPlayer.setVolume(p / 100f, p / 100f)
            b.videoVolValue.text = "$p%"
        })
        b.ttsVol.setOnSeekBarChangeListener(volumeListener { p ->
            ttsPlayer?.setVolume(p / 100f, p / 100f)
            b.ttsVolValue.text = "$p%"
        })
    }

    private fun volumeListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(sb: SeekBar) { showControls() }
        override fun onStopTrackingTouch(sb: SeekBar) {}
    }

    private fun togglePlay() {
        if (!prepared) return
        if (mediaPlayer.isPlaying) {
            mediaPlayer.pause()
            ttsPlayer?.pause()
            b.btnPlay.setImageResource(R.drawable.ic_play)
            showControls()
        } else {
            mediaPlayer.start()
            b.btnPlay.setImageResource(R.drawable.ic_pause)
            scheduleHideControls()
        }
    }

    private fun showControls() {
        controlsVisible = true
        b.controls.visibility = View.VISIBLE
        updateSubtitleOffset()
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, 4500)
    }

    private fun hideControls() {
        controlsVisible = false
        b.controls.visibility = View.GONE
        updateSubtitleOffset()
        mainHandler.removeCallbacks(hideControlsRunnable)
    }

    private fun scheduleHideControls() {
        mainHandler.removeCallbacks(hideControlsRunnable)
        mainHandler.postDelayed(hideControlsRunnable, 4500)
    }

    private fun updateSubtitleOffset() {
        val lp = b.subtitleText.layoutParams as ViewGroup.MarginLayoutParams
        val density = resources.displayMetrics.density
        val controlsH = if (b.controls.visibility == View.VISIBLE && b.controls.height > 0)
            b.controls.height + (16 * density).toInt()
        else (16 * density).toInt()
        lp.bottomMargin = controlsH
        b.subtitleText.layoutParams = lp
    }

    // ---------- subtitles ----------

    private fun pickSubtitle() {
        if (generating) return
        subtitlePicker.launch(arrayOf("*/*"))
    }

    private fun onSubtitlePicked(uri: Uri) {
        val appCtx = applicationContext
        Thread {
            val result = readSubtitle(uri)
            mainHandler.post {
                if (isFinishing || isDestroyed) return@post
                if (result == null) {
                    Toast.makeText(this, R.string.player_bad_subtitle, Toast.LENGTH_SHORT).show()
                    return@post
                }
                val (name, text) = result
                val outcome = runCatching { SubtitleParser.parse(name, text) }
                val parsed = outcome.getOrNull()
                if (parsed == null) {
                    val why = outcome.exceptionOrNull()?.toString() ?: "unknown error"
                    Toast.makeText(this, getString(R.string.player_subtitle_error, name, why), Toast.LENGTH_LONG).show()
                    return@post
                }
                if (parsed.cues.isEmpty()) {
                    Toast.makeText(this, getString(R.string.player_no_subtitles_fmt, name, text.length), Toast.LENGTH_LONG).show()
                    return@post
                }
                track = parsed
                lastShownCue = null
                b.ttsChip.alpha = 1f
                updateSubtitle(if (prepared) mediaPlayer.currentPosition else 0)
                beginTts(parsed, name, uri)
            }
        }.start()
    }

    private fun readSubtitle(uri: Uri): Pair<String, String>? {
        return try {
            var name = "subtitle.srt"
            var size = 0L
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val nIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sIdx = c.getColumnIndex(OpenableColumns.SIZE)
                    if (nIdx >= 0) name = c.getString(nIdx) ?: name
                    if (sIdx >= 0) size = c.getLong(sIdx)
                }
            }
            if (size > MAX_SUBTITLE_BYTES) {
                null
            } else {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) null else Pair(name, SubtitleParser.decode(bytes))
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun beginTts(parsed: SubtitleTrack, subName: String, subUri: Uri) {
        val key = TtsGenerator.buildKey(
            listOf(
                videoUri.toString(),
                declaredDuration.toString(),
                subUri.toString(),
                subName
            )
        )

        // ---- cache lookup ----
        val index = TtsIndex(this)
        val cached = index.findByKey(key)
        if (cached != null) {
            val cachedUri = Uri.parse(cached.uri)
            val exists = try {
                contentResolver.openFileDescriptor(cachedUri, "r")?.use { true } ?: false
            } catch (t: Throwable) { false }
            if (exists) {
                loadTtsTrack(cachedUri, cached.durationMs)
                Toast.makeText(this, R.string.player_tts_loaded_cache, Toast.LENGTH_SHORT).show()
                return
            }
            index.remove(cached.uri)
        }

        // ---- generate ----
        val duration = if (prepared && mediaPlayer.duration > 0) mediaPlayer.duration else declaredDuration
        showGeneratingDialog()
        generator.generate(
            parsed.cues,
            TtsGenerator.Meta(key, videoTitle, subName, duration.toLong()),
            object : TtsGenerator.Listener {
                override fun onProgress(done: Int, total: Int, preparing: Boolean) {
                    val db = dialogBinding ?: return
                    if (preparing || total <= 0) {
                        db.dlgSub.setText(R.string.dlg_generating_preparing)
                        db.dlgProgress.isIndeterminate = true
                        db.dlgPercent.text = ""
                    } else {
                        db.dlgProgress.isIndeterminate = false
                        db.dlgProgress.max = total
                        db.dlgProgress.progress = done
                        db.dlgSub.text = getString(R.string.dlg_generating_fmt, done, total)
                        db.dlgPercent.text = "${done * 100 / total}%"
                    }
                }

                override fun onDone(uri: Uri, durationMs: Long) {
                    dismissGenerating()
                    loadTtsTrack(uri, durationMs)
                    Toast.makeText(this@PlayerActivity, R.string.player_tts_generated, Toast.LENGTH_LONG).show()
                }

                override fun onError(message: String) {
                    dismissGenerating()
                    Toast.makeText(
                        this@PlayerActivity,
                        getString(R.string.player_tts_error, message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun showGeneratingDialog() {
        generating = true
        val db = DialogTtsGenerateBinding.inflate(layoutInflater)
        dialogBinding = db
        db.dlgProgress.isIndeterminate = true
        db.dlgSub.setText(R.string.dlg_generating_preparing)
        db.dlgCancel.setOnClickListener {
            generator.cancel()
            dismissGenerating()
        }
        progressDialog = MaterialAlertDialogBuilder(this)
            .setView(db.root)
            .setCancelable(false)
            .create()
        progressDialog?.show()
    }

    private fun dismissGenerating() {
        generating = false
        try { progressDialog?.dismiss() } catch (t: Throwable) {}
        progressDialog = null
        dialogBinding = null
    }

    // ---------- TTS playback ----------

    private fun loadTtsTrack(uri: Uri, durationMs: Long) {
        try { ttsPlayer?.release() } catch (t: Throwable) {}
        ttsPlayer = null
        ttsReady = false

        val p = MediaPlayer()
        try {
            p.setDataSource(this, uri)
        } catch (t: Throwable) {
            Toast.makeText(this, R.string.player_error_play, Toast.LENGTH_SHORT).show()
            return
        }
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build()
        )
        p.setOnPreparedListener { mp ->
            ttsReady = true
            mp.setVolume(b.ttsVol.progress / 100f, b.ttsVol.progress / 100f)
            val pos = if (prepared) mediaPlayer.currentPosition else 0
            try { mp.seekTo(pos) } catch (t: Throwable) {}
            if (prepared && mediaPlayer.isPlaying) mp.start()
            updateTtsChip()
        }
        p.setOnErrorListener { _, _, _ ->
            ttsReady = false
            updateTtsChip()
            true
        }
        p.prepareAsync()
        ttsPlayer = p
        ttsEnabled = true
        updateTtsChip()
    }

    private fun toggleTts() {
        ttsEnabled = !ttsEnabled
        if (!ttsEnabled) ttsPlayer?.pause()
        else forceTtsTo(if (prepared) mediaPlayer.currentPosition.toLong() else 0L)
        updateTtsChip()
    }

    private fun updateTtsChip() {
        if (ttsPlayer == null) {
            b.ttsChip.alpha = 0.4f
            b.ttsChip.setText(R.string.player_tts_off)
            return
        }
        b.ttsChip.alpha = 1f
        if (ttsEnabled) {
            b.ttsChip.setText(R.string.player_tts_active)
            b.ttsChip.setBackgroundResource(R.drawable.bg_pill_accent)
        } else {
            b.ttsChip.setText(R.string.player_tts_off)
            b.ttsChip.setBackgroundResource(R.drawable.bg_pill)
        }
    }

    /** Keeps the generated track locked to the video clock (seek + drift correction). */
    private fun syncTts(pos: Int) {
        val tp = ttsPlayer ?: return
        if (!ttsReady) return

        val videoPlaying = prepared && mediaPlayer.isPlaying
        if (!ttsEnabled || !videoPlaying) {
            if (tp.isPlaying) tp.pause()
            return
        }

        val ttsDuration = try { tp.duration } catch (t: Throwable) { 0 }
        if (ttsDuration in 1 until (pos + 150)) {
            if (tp.isPlaying) tp.pause()   // track ended before the video; don't restart
            return
        }

        val diff = tp.currentPosition - pos
        val now = SystemClock.elapsedRealtime()
        if (abs(diff) > 350 && now - lastTtsSeekAt > 700) {
            lastTtsSeekAt = now
            try { tp.seekTo(pos) } catch (t: Throwable) {}
        }
        if (!tp.isPlaying) tp.start()
    }

    private fun forceTtsTo(pos: Long) {
        val tp = ttsPlayer ?: return
        if (!ttsReady) return
        lastTtsSeekAt = SystemClock.elapsedRealtime()
        try { tp.seekTo(pos.toInt()) } catch (t: Throwable) {}
        if (ttsEnabled && prepared && mediaPlayer.isPlaying) tp.start()
    }

    private fun updateSubtitle(pos: Int) {
        val cue = track?.cueAt(pos.toLong())
        if (cue != lastShownCue) {
            lastShownCue = cue
            if (cue == null) {
                b.subtitleText.visibility = View.INVISIBLE
            } else {
                b.subtitleText.text = cue.text
                b.subtitleText.visibility = View.VISIBLE
                updateSubtitleOffset()
            }
        }
    }

    // ---------- lifecycle ----------

    override fun onPause() {
        super.onPause()
        resumeAfterWindow = prepared && mediaPlayer.isPlaying
        if (resumeAfterWindow) {
            mediaPlayer.pause()
            ttsPlayer?.pause()
            b.btnPlay.setImageResource(R.drawable.ic_play)
        }
        mainHandler.removeCallbacks(ticker)
        mainHandler.removeCallbacks(hideControlsRunnable)
    }

    override fun onResume() {
        super.onResume()
        mainHandler.post(ticker)
        if (resumeAfterWindow && prepared) {
            resumeAfterWindow = false
            mediaPlayer.start()
            ttsPlayer?.let { if (ttsEnabled) it.start() }
            b.btnPlay.setImageResource(R.drawable.ic_pause)
            scheduleHideControls()
        }
        enterImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(ticker)
        mainHandler.removeCallbacks(hideControlsRunnable)
        if (generating) generator.cancel()
        dismissGenerating()
        try { mediaPlayer.release() } catch (t: Throwable) {}
        try { ttsPlayer?.release() } catch (t: Throwable) {}
        ttsPlayer = null
        surface?.release()
        surface = null
        super.onDestroy()
    }

    // ---------- misc ----------

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, b.root)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (t: Throwable) { null }
}