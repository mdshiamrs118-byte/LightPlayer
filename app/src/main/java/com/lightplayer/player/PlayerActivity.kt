package com.lightplayer.player

import android.graphics.Matrix
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.ScaleGestureDetector
import androidx.core.content.ContextCompat
import com.lightplayer.util.PositionStore
import com.lightplayer.util.Prefs
import kotlin.math.abs
import kotlin.math.roundToInt
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.lightplayer.util.TsCleaner
import com.lightplayer.theme.ThemeManager
import com.lightplayer.R
import com.lightplayer.databinding.ActivityPlayerBinding
import com.lightplayer.databinding.DialogTtsGenerateBinding
import com.lightplayer.subtitle.Cue
import com.lightplayer.subtitle.SubtitleParser
import com.lightplayer.subtitle.SubtitleTrack
import com.lightplayer.tts.TtsGenerator
import com.lightplayer.tts.TtsCache
import com.lightplayer.tts.TtsIndex
import com.lightplayer.util.Formats
import java.util.Locale

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_DURATION = "extra_duration"
        private const val MAX_SUBTITLE_BYTES = 8L * 1024 * 1024
        private const val SEEK_STEP_MS = 10_000L

        const val EXTRA_PLAYLIST_URIS = "extra_playlist_uris"
        const val EXTRA_PLAYLIST_TITLES = "extra_playlist_titles"
        const val EXTRA_PLAYLIST_DURATIONS = "extra_playlist_durations"
        const val EXTRA_PLAYLIST_INDEX = "extra_playlist_index"
    }

    private enum class Aspect(val labelRes: Int) {
        FIT(R.string.aspect_fit),
        FILL(R.string.aspect_fill),
        STRETCH(R.string.aspect_stretch),
        R16_9(R.string.aspect_169),
        R4_3(R.string.aspect_43)
    }

    private class ExtraAudio(val uri: Uri, val name: String)

    private class EmbeddedAudio(val group: Tracks.Group, val trackIndex: Int, val label: String)

    private lateinit var b: ActivityPlayerBinding

    private var videoUri: Uri? = null
    private var videoTitle = ""
    private var declaredDuration = 0

    private lateinit var exo: ExoPlayer
    private var released = false
    private var ready = false
    private var videoW = 0f
    private var videoH = 0f
    private var userDragging = false
    private var resumeAfterWindow = false

    // The other videos in the folder (empty = just this one).
    private var playlistUris: List<Uri> = emptyList()
    private var playlistTitles: List<String> = emptyList()
    private var playlistDurations: IntArray = IntArray(0)
    private var playlistIndex = 0
    private var loadToken = 0
    private var lastSaveAt = 0L

    private var speed = 1f
    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    private var aspect = Aspect.FIT
    private var inBackground = false
    private lateinit var audioManager: AudioManager

    // Swipe gestures: left side = brightness, right side = volume.
    private var swipeMode = 0
    private var swipeBrightness = 0.5f
    private var swipeVolume = 0f
    private val hideHudRunnable = Runnable { if (!released) b.gestureHud.visibility = View.GONE }

    private val controlReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (released) return
            when (intent.action) {
                PlaybackService.ACTION_TOGGLE -> { togglePlay(); updateNotification() }
                PlaybackService.ACTION_CLOSE -> finish()
            }
        }
    }

    // Generated TTS track + user-added audio tracks (both follow the video clock).
    private lateinit var tts: SyncedAudio
    private lateinit var extra: SyncedAudio
    private val extraAudios = ArrayList<ExtraAudio>()
    private var selectedExtra = -1      // -1 = the video's own audio is active
    private var loadedExtra = -1
    private var embeddedAudio: List<EmbeddedAudio> = emptyList()

    private var track: SubtitleTrack? = null
    private var lastShownCue: Cue? = null

    private lateinit var generator: TtsGenerator
    private var progressDialog: androidx.appcompat.app.AlertDialog? = null
    private var dialogBinding: DialogTtsGenerateBinding? = null
    private var generating = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var controlsVisible = true
    private val hideControlsRunnable = Runnable { hideControls() }
    private val hideHintRunnable = Runnable { if (!released) b.seekHint.visibility = View.GONE }

    private val ticker = object : Runnable {
        override fun run() {
            if (released) return
            if (ready) {
                val pos = exo.currentPosition.toInt()
                val dur = durationMs()
                if (!userDragging) {
                    b.seekBar.progress = pos
                    b.timeElapsed.text = Formats.time(pos.toLong())
                    if (dur > 0) b.timeRemaining.text = "-" + Formats.time((dur - pos).toLong())
                }
                updateSubtitle(pos)
                val playing = exo.isPlaying
                tts.sync(pos.toLong(), playing)
                extra.sync(pos.toLong(), playing)
                val now = SystemClock.elapsedRealtime()
                if (playing && now - lastSaveAt > 5_000L) {
                    lastSaveAt = now
                    savePosition()
                }
            }
            mainHandler.postDelayed(this, 250)
        }
    }

    private val subtitlePicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onSubtitlePicked(uri)
        }

    private val audioPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) onAudioPicked(uri)
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
        speed = Prefs.playbackSpeed(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersive()
        applyCutoutInsets()

        applyAccent()
        b.videoTitle.text = videoTitle
        b.buffering.visibility = View.GONE
        b.ttsChip.alpha = 0.4f

        generator = TtsGenerator(this)
        tts = SyncedAudio(this, speech = true)
        extra = SyncedAudio(this, speech = false)
        tts.volume = b.ttsVol.progress / 100f
        extra.volume = b.videoVol.progress / 100f
        tts.speed = speed
        extra.speed = speed

        readPlaylist()
        registerControlReceiver()
        setupPlayer()
        setupControls()
        setupGestures()

        showControls()
        mainHandler.post(ticker)
    }

    // ---------- theme ----------

    private fun applyAccent() {
        val p = ThemeManager.palette(this, forceDark = true)
        val accent = android.content.res.ColorStateList.valueOf(p.accent)
        val accent2 = android.content.res.ColorStateList.valueOf(p.accent2)
        listOf(b.seekBar, b.videoVol).forEach {
            it.progressTintList = accent
            it.thumbTintList = accent
        }
        b.ttsVol.progressTintList = accent2
        b.ttsVol.thumbTintList = accent2
        b.buffering.indeterminateTintList = accent
    }

    // ---------- player ----------

    private fun setupPlayer() {
        val factory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)

        exo = ExoPlayer.Builder(this, factory).build()
        exo.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ true
        )
        exo.setHandleAudioBecomingNoisy(true)
        exo.setWakeMode(C.WAKE_MODE_LOCAL)
        exo.setVideoTextureView(b.videoSurface)
        exo.setPlaybackSpeed(speed)
        applyVolumes()

        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (released) return
                b.buffering.visibility =
                    if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                if (state == Player.STATE_READY && !ready) {
                    ready = true
                    val dur = durationMs()
                    if (dur > 0) {
                        b.seekBar.max = dur
                        if (declaredDuration <= 0) declaredDuration = dur
                    }
                    scheduleHideControls()
                }
                if (state == Player.STATE_ENDED) onVideoEnded()
                updatePlayIcon()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (released) return
                updatePlayIcon()
                updateNotification()
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (released) return
                if (size.width > 0 && size.height > 0) {
                    var w = size.width * size.pixelWidthHeightRatio
                    var h = size.height.toFloat()
                    if (size.unappliedRotationDegrees == 90 || size.unappliedRotationDegrees == 270) {
                        val t = w; w = h; h = t
                    }
                    videoW = w
                    videoH = h
                    updateVideoTransform()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (released) return
                embeddedAudio = collectAudioTracks(tracks)
            }

            override fun onPlayerError(error: PlaybackException) {
                if (released) return
                b.buffering.visibility = View.GONE
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.player_error_play_fmt, error.errorCodeName),
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        })

        b.videoSurface.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateVideoTransform() }

        b.buffering.visibility = View.VISIBLE
        startPlayback()
    }

    /**
     * Some downloaded videos are MPEG-TS streams hidden behind fake PNG headers. Check for that
     * off the main thread and, if so, play through a data source that skips the junk.
     */
    private fun startPlayback() {
        val uri = videoUri ?: return
        val appCtx = applicationContext
        val token = ++loadToken
        val startMs = savedStart(uri)
        if (startMs > 0L) {
            Toast.makeText(this, getString(R.string.player_resumed_fmt, Formats.time(startMs)), Toast.LENGTH_SHORT).show()
        }
        Thread {
            val layout = try { TsCleaner.analyze(appCtx, uri) } catch (_: Throwable) { null }
            mainHandler.post {
                if (released || token != loadToken) return@post
                if (layout != null) {
                    val factory = DataSource.Factory { TsCleanDataSource(appCtx, layout) }
                    val item = MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.VIDEO_MP2T).build()
                    val source = ProgressiveMediaSource.Factory(factory, DefaultExtractorsFactory())
                        .createMediaSource(item)
                    exo.setMediaSource(source, startMs)
                } else {
                    exo.setMediaItem(MediaItem.fromUri(uri), startMs)
                }
                exo.playWhenReady = true
                exo.prepare()
            }
        }.start()
    }

    private fun durationMs(): Int {
        val d = exo.duration
        return if (d != C.TIME_UNSET && d > 0) d.toInt() else declaredDuration
    }

    private fun updatePlayIcon() {
        val showPause = exo.playWhenReady && exo.playbackState != Player.STATE_ENDED
        b.btnPlay.setImageResource(if (showPause) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateVideoTransform() {
        if (released || videoW <= 0f || videoH <= 0f) return
        val vw = b.videoSurface.width.toFloat()
        val vh = b.videoSurface.height.toFloat()
        if (vw <= 0f || vh <= 0f) return
        val matrix = Matrix()
        if (aspect != Aspect.STRETCH) {
            val w = when (aspect) {
                Aspect.R16_9 -> videoH * 16f / 9f
                Aspect.R4_3 -> videoH * 4f / 3f
                else -> videoW
            }
            val h = videoH
            val scale = if (aspect == Aspect.FILL) maxOf(vw / w, vh / h) else minOf(vw / w, vh / h)
            matrix.setScale(w * scale / vw, h * scale / vh, vw / 2f, vh / 2f)
        }
        b.videoSurface.setTransform(matrix)
    }

    // ---------- playlist and resume ----------

    private fun readPlaylist() {
        val uris = intent.getStringArrayListExtra(EXTRA_PLAYLIST_URIS)
        val titles = intent.getStringArrayListExtra(EXTRA_PLAYLIST_TITLES)
        val durations = intent.getLongArrayExtra(EXTRA_PLAYLIST_DURATIONS)
        val index = intent.getIntExtra(EXTRA_PLAYLIST_INDEX, 0)
        if (uris != null && titles != null && durations != null &&
            uris.size > 1 && uris.size == titles.size && uris.size == durations.size && index in uris.indices
        ) {
            playlistUris = uris.map { Uri.parse(it) }
            playlistTitles = titles
            playlistDurations = IntArray(durations.size) { durations[it].toInt() }
            playlistIndex = index
        }
        updateNavButtons()
    }

    private fun hasPrev() = playlistIndex > 0
    private fun hasNext() = playlistIndex + 1 < playlistUris.size

    private fun updateNavButtons() {
        val multi = playlistUris.size > 1
        b.btnPrev.visibility = if (multi) View.VISIBLE else View.GONE
        b.btnNext.visibility = if (multi) View.VISIBLE else View.GONE
        b.btnPrev.alpha = if (hasPrev()) 1f else 0.35f
        b.btnNext.alpha = if (hasNext()) 1f else 0.35f
    }

    private fun playIndex(index: Int) {
        if (index !in playlistUris.indices) return
        savePosition()
        playlistIndex = index
        openVideo(playlistUris[index], playlistTitles[index], playlistDurations[index])
    }

    /** Switches to another video without leaving the player. */
    private fun openVideo(uri: Uri, title: String, duration: Int) {
        exo.playWhenReady = false
        videoUri = uri
        videoTitle = title
        declaredDuration = duration
        ready = false
        videoW = 0f
        videoH = 0f
        // Subtitles, speech and added audio belonged to the previous video.
        track = null
        lastShownCue = null
        b.subtitleText.visibility = View.INVISIBLE
        tts.release()
        tts.enabled = false
        extra.release()
        extra.enabled = false
        extraAudios.clear()
        selectedExtra = -1
        loadedExtra = -1
        embeddedAudio = emptyList()
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
        applyVolumes()
        updateTtsChip()

        b.videoTitle.text = title
        b.seekBar.max = 100
        b.seekBar.progress = 0
        b.timeElapsed.text = Formats.time(0L)
        b.timeRemaining.text = "-" + Formats.time(0L)
        b.buffering.visibility = View.VISIBLE
        updateNavButtons()
        updateNotification()
        startPlayback()
    }

    private fun onVideoEnded() {
        videoUri?.let { PositionStore.clear(this, it.toString()) }
        if (Prefs.autoNext(this) && hasNext()) {
            Toast.makeText(
                this,
                getString(R.string.player_next_fmt, playlistTitles[playlistIndex + 1]),
                Toast.LENGTH_SHORT
            ).show()
            playIndex(playlistIndex + 1)
        } else {
            if (inBackground) exo.playWhenReady = false
            showControls()
        }
    }

    private fun savedStart(uri: Uri): Long {
        if (!Prefs.resumeEnabled(this)) return 0L
        val entry = PositionStore.get(this, uri.toString()) ?: return 0L
        return (entry.pos - 2_000L).coerceAtLeast(0L)
    }

    private fun savePosition() {
        if (released || !ready || !Prefs.resumeEnabled(this)) return
        val uri = videoUri ?: return
        PositionStore.save(this, uri.toString(), exo.currentPosition, durationMs().toLong())
    }

    // ---------- speed and aspect ----------

    private fun speedLabel(s: Float): String =
        (if (s % 1f == 0f) s.toInt().toString() else s.toString()) + "x"

    private fun showSpeedDialog() {
        showControls()
        val labels = speeds.map { speedLabel(it) }.toTypedArray()
        val checked = speeds.indexOfFirst { abs(it - speed) < 0.01f }
        ThemeManager.themed(
            ThemeManager.dialogBuilder(this, forceDark = true)
                .setTitle(R.string.player_speed_title)
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    setSpeed(speeds[which])
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .create(),
            forceDark = true
        ).show()
    }

    private fun setSpeed(value: Float) {
        speed = value
        exo.setPlaybackSpeed(value)
        tts.speed = value        // keep the spoken track and added audio in step with the video
        extra.speed = value
        Prefs.setPlaybackSpeed(this, value)
        b.btnSpeed.text = speedLabel(value)
        showHud(speedLabel(value))
    }

    private fun setAspect(mode: Aspect) {
        aspect = mode
        b.btnAspect.setText(mode.labelRes)
        updateVideoTransform()
        showHud(getString(mode.labelRes))
    }

    private fun cycleAspect() {
        val all = Aspect.values()
        setAspect(all[(aspect.ordinal + 1) % all.size])
    }

    // ---------- background audio ----------

    private fun registerControlReceiver() {
        val filter = IntentFilter().apply {
            addAction(PlaybackService.ACTION_TOGGLE)
            addAction(PlaybackService.ACTION_CLOSE)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(controlReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(controlReceiver, filter)
        }
    }

    private fun enterBackgroundMode() {
        inBackground = true
        // No point decoding video nobody can see.
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
            .build()
        mainHandler.removeCallbacks(hideControlsRunnable)
        try {
            ContextCompat.startForegroundService(
                this,
                Intent(this, PlaybackService::class.java)
                    .putExtra(PlaybackService.EXTRA_TITLE, videoTitle)
                    .putExtra(PlaybackService.EXTRA_PLAYING, true)
            )
        } catch (_: Throwable) {
        }
    }

    private fun leaveBackgroundMode() {
        inBackground = false
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .build()
        PlaybackService.stop(this)
    }

    private fun updateNotification() {
        if (!inBackground || released) return
        try {
            val playing = exo.playWhenReady && exo.playbackState != Player.STATE_ENDED
            getSystemService(NotificationManager::class.java).notify(
                PlaybackService.NOTIFICATION_ID,
                PlaybackService.buildNotification(this, videoTitle, playing)
            )
        } catch (_: Throwable) {
        }
    }

    // ---------- controls ----------

    private fun setupControls() {
        b.btnPlay.setOnClickListener { togglePlay() }
        b.btnBack.setOnClickListener { finish() }
        b.btnClose.setOnClickListener { finish() }
        b.btnSubs.setOnClickListener { pickSubtitle() }
        b.btnAudio.setOnClickListener { showAudioDialog() }
        b.btnRewind.setOnClickListener { seekBy(-SEEK_STEP_MS) }
        b.btnForward.setOnClickListener { seekBy(SEEK_STEP_MS) }
        b.btnPrev.setOnClickListener { if (hasPrev()) playIndex(playlistIndex - 1) }
        b.btnNext.setOnClickListener { if (hasNext()) playIndex(playlistIndex + 1) }
        b.btnSpeed.text = speedLabel(speed)
        b.btnSpeed.setOnClickListener { showSpeedDialog() }
        b.btnAspect.setText(aspect.labelRes)
        b.btnAspect.setOnClickListener { cycleAspect() }
        b.ttsChip.setOnClickListener {
            if (tts.isLoaded) toggleTts() else pickSubtitle()
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
                if (ready) seekTo(sb.progress.toLong())
                scheduleHideControls()
            }
        })

        b.videoVol.setOnSeekBarChangeListener(volumeListener { p ->
            applyVolumes()
            b.videoVolValue.text = "$p%"
        })
        b.ttsVol.setOnSeekBarChangeListener(volumeListener { p ->
            tts.volume = p / 100f
            b.ttsVolValue.text = "$p%"
        })
    }

    private fun setupGestures() {
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private var accumulated = 1f
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                accumulated = 1f
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                accumulated *= detector.scaleFactor
                return true
            }
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // Pinch out = fill the screen, pinch in = fit the whole picture.
                if (accumulated > 1.12f) setAspect(Aspect.FILL)
                else if (accumulated < 0.88f) setAspect(Aspect.FIT)
            }
        })

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                beginSwipe()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controlsVisible) hideControls() else showControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (e.x < b.videoSurface.width / 2f) seekBy(-SEEK_STEP_MS) else seekBy(SEEK_STEP_MS)
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null || scaleDetector.isInProgress || e2.pointerCount > 1) return false
                if (swipeMode == 0) {
                    // Only mostly-vertical swipes count.
                    if (abs(e2.y - e1.y) <= abs(e2.x - e1.x)) return false
                    swipeMode = if (e1.x < b.videoSurface.width / 2f) 1 else 2
                }
                // distanceY is positive when the finger moves up.
                val delta = distanceY / (b.videoSurface.height * 0.75f)
                if (swipeMode == 1) adjustBrightness(delta) else adjustVolume(delta)
                return true
            }
        })

        b.videoSurface.setOnTouchListener { _, ev ->
            scaleDetector.onTouchEvent(ev)
            detector.onTouchEvent(ev)
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                swipeMode = 0
            }
            true
        }
    }

    private fun beginSwipe() {
        swipeMode = 0
        val current = window.attributes.screenBrightness
        swipeBrightness = if (current >= 0f) current else try {
            Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (_: Throwable) {
            0.5f
        }
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        swipeVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private fun adjustBrightness(delta: Float) {
        swipeBrightness = (swipeBrightness + delta).coerceIn(0.02f, 1f)
        val lp = window.attributes
        lp.screenBrightness = swipeBrightness
        window.attributes = lp
        showHud(getString(R.string.player_brightness_fmt, (swipeBrightness * 100).roundToInt()))
    }

    private fun adjustVolume(delta: Float) {
        swipeVolume = (swipeVolume + delta).coerceIn(0f, 1f)
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (swipeVolume * max).roundToInt(), 0)
        showHud(getString(R.string.player_volume_fmt, (swipeVolume * 100).roundToInt()))
    }

    private fun showHud(text: String) {
        b.gestureHud.text = text
        b.gestureHud.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideHudRunnable)
        mainHandler.postDelayed(hideHudRunnable, 800)
    }

    private fun volumeListener(onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange(progress)
        }
        override fun onStartTrackingTouch(sb: SeekBar) { showControls() }
        override fun onStopTrackingTouch(sb: SeekBar) { scheduleHideControls() }
    }

    /** The volume slider controls whichever audio is currently the "main" one. */
    private fun applyVolumes() {
        val v = b.videoVol.progress / 100f
        exo.volume = if (selectedExtra >= 0) 0f else v
        extra.volume = v
    }

    private fun togglePlay() {
        if (!ready && exo.playbackState == Player.STATE_IDLE) return
        if (exo.playbackState == Player.STATE_ENDED) {
            seekTo(0L)
            exo.playWhenReady = true
            scheduleHideControls()
            return
        }
        if (exo.playWhenReady) {
            exo.playWhenReady = false
            tts.pause()
            extra.pause()
            showControls()
        } else {
            exo.playWhenReady = true
            scheduleHideControls()
        }
    }

    private fun seekTo(posMs: Long) {
        val dur = durationMs().toLong()
        val target = if (dur > 0) posMs.coerceIn(0L, dur) else posMs.coerceAtLeast(0L)
        exo.seekTo(target)
        val playing = exo.playWhenReady
        tts.forceTo(target, playing)
        extra.forceTo(target, playing)
    }

    private fun seekBy(deltaMs: Long) {
        if (!ready) return
        seekTo(exo.currentPosition + deltaMs)
        b.seekHint.text = if (deltaMs > 0) getString(R.string.player_fwd_10) else getString(R.string.player_back_10)
        b.seekHint.visibility = View.VISIBLE
        mainHandler.removeCallbacks(hideHintRunnable)
        mainHandler.postDelayed(hideHintRunnable, 600)
        if (controlsVisible) showControls()
    }

    private fun fadeIn(v: View) {
        v.animate().cancel()
        if (v.visibility != View.VISIBLE) {
            v.alpha = 0f
            v.visibility = View.VISIBLE
        }
        v.animate().alpha(1f).setDuration(180).start()
    }

    private fun fadeOut(v: View) {
        v.animate().cancel()
        v.animate().alpha(0f).setDuration(160).withEndAction {
            if (!controlsVisible) v.visibility = View.GONE
        }.start()
    }

    private fun showControls() {
        controlsVisible = true
        fadeIn(b.controls)
        fadeIn(b.topBar)
        updateSubtitleOffset()
        scheduleHideControls()
    }

    private fun hideControls() {
        controlsVisible = false
        fadeOut(b.controls)
        fadeOut(b.topBar)
        updateSubtitleOffset()
        mainHandler.removeCallbacks(hideControlsRunnable)
    }

    private fun scheduleHideControls() {
        mainHandler.removeCallbacks(hideControlsRunnable)
        if (controlsVisible && exo.playWhenReady) {
            mainHandler.postDelayed(hideControlsRunnable, 4500)
        }
    }

    private fun updateSubtitleOffset() {
        val lp = b.subtitleText.layoutParams as ViewGroup.MarginLayoutParams
        val density = resources.displayMetrics.density
        val controlsH = if (controlsVisible && b.controls.height > 0)
            b.controls.height + (16 * density).toInt()
        else (16 * density).toInt()
        lp.bottomMargin = controlsH
        b.subtitleText.layoutParams = lp
    }

    // ---------- audio tracks ----------

    private fun collectAudioTracks(tracks: Tracks): List<EmbeddedAudio> {
        val out = ArrayList<EmbeddedAudio>()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                out.add(EmbeddedAudio(group, i, ""))
            }
        }
        return out.mapIndexed { idx, e ->
            EmbeddedAudio(e.group, e.trackIndex, audioLabel(e.group.getTrackFormat(e.trackIndex), idx + 1))
        }
    }

    private fun audioLabel(f: Format, number: Int): String {
        val parts = ArrayList<String>()
        val lang = f.language
            ?.takeIf { it.isNotBlank() && it != "und" }
            ?.let { Locale.forLanguageTag(it).displayLanguage }
            ?.takeIf { it.isNotBlank() }
        val label = f.label?.takeIf { it.isNotBlank() }
        if (label != null) parts.add(label)
        if (lang != null && (label == null || !label.contains(lang, ignoreCase = true))) parts.add(lang)
        when (f.channelCount) {
            1 -> parts.add("Mono")
            2 -> parts.add("Stereo")
            6 -> parts.add("5.1")
            8 -> parts.add("7.1")
            else -> if (f.channelCount > 0) parts.add("${f.channelCount}ch")
        }
        f.sampleMimeType?.substringAfter('/')?.takeIf { it.isNotBlank() }?.let { parts.add(it.uppercase()) }
        val title = getString(R.string.player_audio_embedded_fmt, number)
        return if (parts.isEmpty()) title else "$title · ${parts.joinToString(" · ")}"
    }

    private fun showAudioDialog() {
        showControls()
        val labels = ArrayList<String>()
        embeddedAudio.forEach { labels.add(it.label) }
        extraAudios.forEach { labels.add(getString(R.string.player_audio_external_fmt, it.name)) }

        val checked: Int = if (selectedExtra >= 0) {
            embeddedAudio.size + selectedExtra
        } else {
            embeddedAudio.indexOfFirst { it.group.isTrackSelected(it.trackIndex) }
        }

        val builder = ThemeManager.dialogBuilder(this, forceDark = true)
            .setTitle(R.string.player_audio_title)
            .setNeutralButton(R.string.player_audio_add) { _, _ ->
                audioPicker.launch(arrayOf("audio/*", "application/octet-stream"))
            }
            .setNegativeButton(R.string.action_cancel, null)

        if (labels.isEmpty()) {
            builder.setMessage(R.string.player_audio_none)
        } else {
            builder.setSingleChoiceItems(labels.toTypedArray(), checked) { dialog, which ->
                if (which < embeddedAudio.size) selectEmbedded(embeddedAudio[which])
                else selectExtra(which - embeddedAudio.size)
                dialog.dismiss()
            }
        }
        ThemeManager.themed(builder.create(), forceDark = true).show()
    }

    private fun selectEmbedded(entry: EmbeddedAudio) {
        selectedExtra = -1
        extra.enabled = false
        extra.pause()
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(TrackSelectionOverride(entry.group.mediaTrackGroup, entry.trackIndex))
            .build()
        applyVolumes()
    }

    private fun selectExtra(index: Int) {
        val item = extraAudios.getOrNull(index) ?: return
        selectedExtra = index
        val pos = exo.currentPosition
        if (loadedExtra != index || !extra.isLoaded) {
            loadedExtra = index
            extra.load(item.uri, pos, exo.isPlaying) {
                if (released) return@load
                Toast.makeText(this, R.string.player_audio_error, Toast.LENGTH_SHORT).show()
                selectedExtra = -1
                loadedExtra = -1
                extra.enabled = false
                applyVolumes()
            }
        } else {
            extra.forceTo(pos, exo.isPlaying)
        }
        extra.enabled = true
        applyVolumes()
    }

    private fun onAudioPicked(uri: Uri) {
        val name = queryDisplayName(uri) ?: "audio"
        extraAudios.add(ExtraAudio(uri, name))
        selectExtra(extraAudios.size - 1)
        Toast.makeText(this, R.string.player_audio_added, Toast.LENGTH_SHORT).show()
    }

    // ---------- subtitles ----------

    private fun pickSubtitle() {
        if (generating) return
        subtitlePicker.launch(arrayOf("*/*"))
    }

    private fun onSubtitlePicked(uri: Uri) {
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
                updateSubtitle(if (ready) exo.currentPosition.toInt() else 0)
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
                loadTtsTrack(cachedUri)
                Toast.makeText(this, R.string.player_tts_loaded_cache, Toast.LENGTH_SHORT).show()
                return
            }
            index.remove(cached.uri)
        }

        // ---- same subtitle already converted (here or from the Music tab)? ----
        val contentKey = TtsCache.contentKey(parsed.cues)
        val reuse = TtsCache.find(this, index, subName, parsed.cues)
        if (reuse != null) {
            loadTtsTrack(Uri.parse(reuse.uri))
            Toast.makeText(this, R.string.player_tts_loaded_cache, Toast.LENGTH_SHORT).show()
            return
        }

        // ---- generate ----
        val duration = if (ready && durationMs() > 0) durationMs() else declaredDuration
        showGeneratingDialog()
        generator.generate(
            parsed.cues,
            TtsGenerator.Meta(key, videoTitle, subName, duration.toLong(), contentKey),
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
                    if (released) return
                    loadTtsTrack(uri)
                    Toast.makeText(this@PlayerActivity, R.string.player_tts_generated, Toast.LENGTH_LONG).show()
                }

                override fun onError(message: String) {
                    dismissGenerating()
                    if (released) return
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
        ThemeManager.apply(db.root, ThemeManager.palette(this, forceDark = true))
        progressDialog = ThemeManager.themed(
            ThemeManager.dialogBuilder(this, forceDark = true)
                .setView(db.root)
                .setCancelable(false)
                .create(),
            forceDark = true
        )
        progressDialog?.show()
    }

    private fun dismissGenerating() {
        generating = false
        try { progressDialog?.dismiss() } catch (t: Throwable) {}
        progressDialog = null
        dialogBinding = null
    }

    // ---------- TTS playback ----------

    private fun loadTtsTrack(uri: Uri) {
        tts.volume = b.ttsVol.progress / 100f
        tts.enabled = true
        tts.load(uri, if (ready) exo.currentPosition else 0L, exo.isPlaying) {
            if (released) return@load
            Toast.makeText(this, R.string.player_error_play, Toast.LENGTH_SHORT).show()
            tts.enabled = false
            updateTtsChip()
        }
        updateTtsChip()
    }

    private fun toggleTts() {
        tts.enabled = !tts.enabled
        if (!tts.enabled) tts.pause()
        else tts.forceTo(if (ready) exo.currentPosition else 0L, exo.isPlaying)
        updateTtsChip()
    }

    private fun updateTtsChip() {
        if (!tts.isLoaded) {
            b.ttsChip.alpha = 0.4f
            b.ttsChip.setText(R.string.player_tts_off)
            return
        }
        b.ttsChip.alpha = 1f
        if (tts.enabled) {
            b.ttsChip.setText(R.string.player_tts_active)
            val accent = ThemeManager.palette(this, forceDark = true).accent
            b.ttsChip.background = ThemeManager.rounded(this, (accent and 0x00FFFFFF) or 0x44000000, 17f, accent)
        } else {
            b.ttsChip.setText(R.string.player_tts_off)
            b.ttsChip.setBackgroundResource(R.drawable.bg_pill)
        }
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
        if (released) return
        savePosition()
        val keepPlaying = Prefs.backgroundAudio(this) && exo.playWhenReady &&
            !isFinishing && !isChangingConfigurations
        if (keepPlaying) {
            enterBackgroundMode()
            return
        }
        resumeAfterWindow = exo.playWhenReady
        if (resumeAfterWindow) {
            exo.playWhenReady = false
            tts.pause()
            extra.pause()
        }
        mainHandler.removeCallbacks(ticker)
        mainHandler.removeCallbacks(hideControlsRunnable)
    }

    override fun onResume() {
        super.onResume()
        if (released) return
        if (inBackground) leaveBackgroundMode()
        mainHandler.removeCallbacks(ticker)
        mainHandler.post(ticker)
        if (resumeAfterWindow) {
            resumeAfterWindow = false
            exo.playWhenReady = true
            scheduleHideControls()
        }
        enterImmersive()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onDestroy() {
        savePosition()
        released = true
        try { unregisterReceiver(controlReceiver) } catch (_: Throwable) {}
        PlaybackService.stop(this)
        mainHandler.removeCallbacksAndMessages(null)
        if (generating) generator.cancel()
        dismissGenerating()
        // Detach the surface first, then release. Releasing first and letting the
        // TextureView tear itself down afterwards is what used to crash on back.
        try { exo.clearVideoTextureView(b.videoSurface) } catch (_: Throwable) {}
        try { exo.release() } catch (_: Throwable) {}
        tts.release()
        extra.release()
        super.onDestroy()
    }

    // ---------- misc ----------

    private fun applyCutoutInsets() {
        val topBase = intArrayOf(b.topBar.paddingLeft, b.topBar.paddingTop, b.topBar.paddingRight, b.topBar.paddingBottom)
        val ctlBase = intArrayOf(b.controls.paddingLeft, b.controls.paddingTop, b.controls.paddingRight, b.controls.paddingBottom)
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val c = insets.getInsets(
                WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars()
            )
            b.topBar.setPadding(topBase[0] + c.left, topBase[1] + c.top, topBase[2] + c.right, topBase[3])
            b.controls.setPadding(ctlBase[0] + c.left, ctlBase[1], ctlBase[2] + c.right, ctlBase[3] + c.bottom)
            insets
        }
    }

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
    } catch (t: Throwable) { null } ?: uri.lastPathSegment
}
