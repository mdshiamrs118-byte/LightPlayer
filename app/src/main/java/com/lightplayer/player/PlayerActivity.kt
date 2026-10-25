package com.lightplayer.player

import android.graphics.Matrix
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.text.TextUtils
import android.transition.AutoTransition
import android.transition.ChangeBounds
import android.transition.Fade
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.animation.PathInterpolator
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
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

        // Carried over from the floating window when it expands back to this screen.
        const val EXTRA_RESUME_POSITION = "extra_resume_position"
        const val EXTRA_RESUME_PLAYING = "extra_resume_playing"
        const val EXTRA_EMBEDDED_INDEX = "extra_embedded_index"
        const val EXTRA_AUDIO_URI = "extra_audio_uri"
        const val EXTRA_AUDIO_NAME = "extra_audio_name"
        const val EXTRA_TTS_URI = "extra_tts_uri"
        const val EXTRA_TTS_ENABLED = "extra_tts_enabled"
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

    private var pendingStartMs = -1L          // position handed over by the floating window
    private var pendingPlay = true
    private var pendingEmbeddedIndex = -1
    private var ttsUri: Uri? = null

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

        // Opening the full player replaces any floating window.
        PipService.stop(this)
        pendingStartMs = intent.getLongExtra(EXTRA_RESUME_POSITION, -1L)
        pendingPlay = intent.getBooleanExtra(EXTRA_RESUME_PLAYING, true)

        readPlaylist()
        registerControlReceiver()
        setupPlayer()
        setupControls()
        setupGestures()
        restoreFromFloatingWindow()

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
                if (pendingEmbeddedIndex in embeddedAudio.indices) {
                    val wanted = embeddedAudio[pendingEmbeddedIndex]
                    pendingEmbeddedIndex = -1
                    selectEmbedded(wanted)
                }
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
        val startMs = if (pendingStartMs >= 0L) pendingStartMs.also { pendingStartMs = -1L } else savedStart(uri)
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
                exo.playWhenReady = pendingPlay
                pendingPlay = true
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
        updatePinnedValues()
        showHud(speedLabel(value))
    }

    private fun setAspect(mode: Aspect) {
        aspect = mode
        updatePinnedValues()
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

    // ---------- floating window (custom picture-in-picture) ----------

    /** Hands the video over to the floating window and leaves this screen. */
    private fun startFloatingWindow() {
        if (released) return
        if (!Settings.canDrawOverlays(this)) {
            showOverlayPermissionDialog()
            return
        }
        val uri = videoUri ?: return
        savePosition()

        val extraItem = extraAudios.getOrNull(selectedExtra)
        val embeddedIndex = if (selectedExtra >= 0) -1
        else embeddedAudio.indexOfFirst { it.group.isTrackSelected(it.trackIndex) }

        PipService.start(
            this,
            PipService.Session(
                uri = uri,
                title = videoTitle,
                durationMs = durationMs().toLong(),
                positionMs = exo.currentPosition,
                playing = exo.playWhenReady && exo.playbackState != Player.STATE_ENDED,
                speed = speed,
                volume = b.videoVol.progress / 100f,
                embeddedIndex = embeddedIndex,
                extraAudioUri = extraItem?.uri,
                extraAudioName = extraItem?.name,
                ttsUri = if (tts.isLoaded) ttsUri else null,
                ttsEnabled = tts.enabled,
                ttsVolume = b.ttsVol.progress / 100f,
                aspect = if (videoW > 0f && videoH > 0f) videoW / videoH else 0f,
                playlistUris = playlistUris.map { it.toString() },
                playlistTitles = playlistTitles,
                playlistDurations = LongArray(playlistDurations.size) { playlistDurations[it].toLong() },
                playlistIndex = playlistIndex
            )
        )

        // The floating window plays from here on; silence this screen and leave.
        exo.playWhenReady = false
        tts.pause()
        extra.pause()
        finish()
    }

    private fun showOverlayPermissionDialog() {
        ThemeManager.themed(
            ThemeManager.dialogBuilder(this, forceDark = true)
                .setTitle(R.string.pip_permission_title)
                .setMessage(R.string.pip_permission_message)
                .setPositiveButton(R.string.pip_permission_open) { _, _ ->
                    try {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                        )
                    } catch (_: Throwable) {
                    }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .create(),
            forceDark = true
        ).show()
    }

    /** When the floating window expands back to this screen, bring its audio choices along. */
    private fun restoreFromFloatingWindow() {
        val extraUri = intent.getStringExtra(EXTRA_AUDIO_URI)
        if (extraUri != null) {
            extraAudios.add(ExtraAudio(Uri.parse(extraUri), intent.getStringExtra(EXTRA_AUDIO_NAME) ?: "audio"))
            selectExtra(0)
        } else {
            pendingEmbeddedIndex = intent.getIntExtra(EXTRA_EMBEDDED_INDEX, -1)
        }
        val speech = intent.getStringExtra(EXTRA_TTS_URI)
        if (speech != null) {
            loadTtsTrack(Uri.parse(speech))
            tts.enabled = intent.getBooleanExtra(EXTRA_TTS_ENABLED, true)
            updateTtsChip()
        }
    }

    /** Optional: start the floating window by itself when the user leaves the app mid-video. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!released && !isFinishing && Prefs.autoPip(this) && exo.isPlaying &&
            Settings.canDrawOverlays(this)
        ) {
            startFloatingWindow()
        }
    }

    // ---------- player menu (gear button) ----------

    /**
     * One entry in the gear menu. To add a button to the player in the future, add one of these
     * to the list in [setupMenu]. It can also be pinned next to the gear as a quick button.
     */
    private class PlayerAction(
        val id: String,
        val iconRes: Int,
        val labelRes: Int,
        val descRes: Int,
        val value: (() -> String)? = null,
        /** Pinned as a small text pill showing [value] instead of an icon. */
        val valueAsPill: Boolean = false,
        val run: () -> Unit
    )

    private var actions: List<PlayerAction> = emptyList()
    private var menuOpen = false
    private var menuExpanded = false
    private lateinit var menuBackCallback: OnBackPressedCallback

    // Views that only exist while the menu is expanded (names, values, hint).
    private val menuDetailViews = ArrayList<View>()
    private val menuBadges = LinkedHashMap<String, TextView>()
    private val menuIcons = LinkedHashMap<String, ImageView>()

    private val pinnedViews = LinkedHashMap<String, View>()
    private val pinnedValueViews = ArrayList<Pair<PlayerAction, TextView>>()

    private val menuEase = PathInterpolator(0.4f, 0f, 0.2f, 1f)

    private fun dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun setupMenu() {
        actions = listOf(
            PlayerAction(
                id = "speed", iconRes = R.drawable.ic_speed_fwd,
                labelRes = R.string.player_speed_title, descRes = R.string.menu_speed_desc,
                value = { speedLabel(speed) }, valueAsPill = true, run = { showSpeedDialog() }
            ),
            PlayerAction(
                id = "aspect", iconRes = R.drawable.ic_aspect,
                labelRes = R.string.menu_aspect, descRes = R.string.menu_aspect_desc,
                value = { getString(aspect.labelRes) }, valueAsPill = true, run = { cycleAspect() }
            ),
            PlayerAction(
                id = "audio", iconRes = R.drawable.ic_music,
                labelRes = R.string.menu_audio, descRes = R.string.menu_audio_desc,
                run = { showAudioDialog() }
            ),
            PlayerAction(
                id = "subtitles", iconRes = R.drawable.ic_subtitles,
                labelRes = R.string.menu_subtitles, descRes = R.string.menu_subtitles_desc,
                run = { pickSubtitle() }
            ),
            PlayerAction(
                id = "pip", iconRes = R.drawable.ic_pip,
                labelRes = R.string.menu_pip, descRes = R.string.menu_pip_desc,
                run = { startFloatingWindow() }
            ),
            PlayerAction(
                id = "tts", iconRes = R.drawable.ic_tts,
                labelRes = R.string.menu_tts, descRes = R.string.menu_tts_desc,
                value = { getString(if (tts.isLoaded && tts.enabled) R.string.menu_on else R.string.menu_off) },
                run = { if (tts.isLoaded) toggleTts() else pickSubtitle() }
            )
        )

        val p = ThemeManager.palette(this, forceDark = true)
        val surface = (p.surface and 0x00FFFFFF) or 0xF2000000.toInt()
        b.menuPanel.background = ThemeManager.rounded(this, surface, 24f, p.stroke)
        b.menuPanel.clipToOutline = true

        val toggle = View.OnClickListener { setMenuExpanded(!menuExpanded, animate = true) }
        b.btnExpand.setOnClickListener(toggle)
        b.menuHeader.setOnClickListener(toggle)

        menuBackCallback = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() { closeMenu() }
        }
        onBackPressedDispatcher.addCallback(this, menuBackCallback)

        refreshPinned()
    }

    private fun selectableBackground(borderless: Boolean): Drawable? {
        val tv = TypedValue()
        val attr = if (borderless) android.R.attr.selectableItemBackgroundBorderless
        else android.R.attr.selectableItemBackground
        if (!theme.resolveAttribute(attr, tv, true) || tv.resourceId == 0) return null
        return ContextCompat.getDrawable(this, tv.resourceId)
    }

    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    /**
     * Rows keep their icon on the right edge, so when the panel grows leftwards the icons
     * stay put and the names slide out beside them.
     */
    private fun buildMenuRows() {
        val list = b.menuList
        list.removeAllViews()
        menuDetailViews.clear()
        menuBadges.clear()
        menuIcons.clear()

        val pinned = Prefs.pinnedActions(this)
        val accent = ThemeManager.palette(this, forceDark = true).accent
        val detailVisibility = if (menuExpanded) View.VISIBLE else View.GONE
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        actions.forEach { action ->
            val isPinned = action.id in pinned
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dpi(52)
                setPadding(dpi(16), dpi(8), dpi(16), dpi(8))
                background = selectableBackground(borderless = false)
                isClickable = true
                isFocusable = true
                contentDescription = getString(action.labelRes)

                val texts = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = detailVisibility
                    addView(TextView(context).apply {
                        setText(action.labelRes)
                        setTextColor(Color.WHITE)
                        textSize = 15f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                    addView(TextView(context).apply {
                        setText(action.descRes)
                        setTextColor(0xFF8D929B.toInt())
                        textSize = 11f
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    })
                }
                menuDetailViews.add(texts)
                addView(texts, lp(0, wrap).also { it.weight = 1f; it.marginEnd = dpi(10) })

                action.value?.let { read ->
                    val value = TextView(context).apply {
                        text = read()
                        setTextColor(0xFFB8BCC4.toInt())
                        textSize = 13f
                        maxLines = 1
                        visibility = detailVisibility
                    }
                    menuDetailViews.add(value)
                    addView(value, lp(wrap, wrap).also { it.marginEnd = dpi(10) })
                }

                val badge = TextView(context).apply {
                    text = "\uD83D\uDCCC"
                    textSize = 12f
                    visibility = if (menuExpanded && isPinned) View.VISIBLE else View.GONE
                }
                menuBadges[action.id] = badge
                addView(badge, lp(wrap, wrap).also { it.marginEnd = dpi(8) })

                val icon = ImageView(context).apply {
                    setImageResource(action.iconRes)
                    val tint = if (isPinned) accent else Color.WHITE
                    imageTintList = ColorStateList.valueOf(tint)
                    tag = tint
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                menuIcons[action.id] = icon
                addView(icon, lp(dpi(24), dpi(24)))

                setOnClickListener {
                    closeMenu()
                    action.run()
                }
                setOnLongClickListener {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    togglePin(action)
                    true
                }
            }
            list.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT, wrap))
        }

        val hint = TextView(this).apply {
            setText(R.string.menu_hint)
            setTextColor(0xFF8D929B.toInt())
            textSize = 11f
            setPadding(dpi(16), dpi(8), dpi(16), dpi(8))
            visibility = detailVisibility
        }
        menuDetailViews.add(hint)
        list.addView(hint, lp(ViewGroup.LayoutParams.MATCH_PARENT, wrap))
    }

    /** Expands the rail into the full menu with names, or folds it back to icons. */
    private fun setMenuExpanded(expanded: Boolean, animate: Boolean) {
        menuExpanded = expanded
        if (animate) {
            TransitionManager.beginDelayedTransition(
                b.playerRoot,
                TransitionSet().apply {
                    addTransition(ChangeBounds())
                    addTransition(Fade())
                    duration = 300
                    interpolator = menuEase
                }
            )
        }
        val params = b.menuPanel.layoutParams
        params.width = dpi(if (expanded) 276 else 56)
        b.menuPanel.layoutParams = params

        b.menuTitle.visibility = if (expanded) View.VISIBLE else View.GONE
        menuDetailViews.forEach { it.visibility = if (expanded) View.VISIBLE else View.GONE }
        val pinned = Prefs.pinnedActions(this)
        menuBadges.forEach { (id, badge) ->
            badge.visibility = if (expanded && id in pinned) View.VISIBLE else View.GONE
        }

        val target = if (expanded) 180f else 0f
        b.btnExpand.animate().cancel()
        if (animate) {
            b.btnExpand.animate().rotation(target).setDuration(300).setInterpolator(menuEase).start()
        } else {
            b.btnExpand.rotation = target
        }
    }

    private fun togglePin(action: PlayerAction) {
        val ids = Prefs.pinnedActions(this).toMutableList()
        val label = getString(action.labelRes)
        val nowPinned: Boolean
        if (ids.remove(action.id)) {
            nowPinned = false
            Prefs.setPinnedActions(this, ids)
            removePinned(action.id)
            showHud(getString(R.string.hud_unpinned_fmt, label))
        } else {
            nowPinned = true
            ids.add(action.id)
            Prefs.setPinnedActions(this, ids)
            addPinned(action)
            showHud(getString(R.string.hud_pinned_fmt, label))
        }

        // Update the open menu in place (no rebuild, so nothing flickers).
        if (menuOpen) {
            TransitionManager.beginDelayedTransition(b.menuList, AutoTransition().setDuration(220))
            menuBadges[action.id]?.visibility =
                if (menuExpanded && nowPinned) View.VISIBLE else View.GONE
            menuIcons[action.id]?.let { icon ->
                val accent = ThemeManager.palette(this, forceDark = true).accent
                tintIcon(icon, if (nowPinned) accent else Color.WHITE)
            }
        }
    }

    private fun tintIcon(icon: ImageView, to: Int) {
        val from = (icon.tag as? Int) ?: Color.WHITE
        icon.tag = to
        ValueAnimator.ofObject(ArgbEvaluator(), from, to).apply {
            duration = 250
            addUpdateListener { icon.imageTintList = ColorStateList.valueOf(it.animatedValue as Int) }
            start()
        }
    }

    // ---------- pinned quick buttons ----------

    private fun pinnedParams(action: PlayerAction): LinearLayout.LayoutParams = lp(
        if (action.valueAsPill) ViewGroup.LayoutParams.WRAP_CONTENT else dpi(40),
        if (action.valueAsPill) dpi(32) else dpi(40)
    ).also { it.marginStart = dpi(4) }

    private fun createPinnedView(action: PlayerAction): View {
        val view: View
        if (action.valueAsPill && action.value != null) {
            val pill = TextView(this).apply {
                text = action.value.invoke()
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                minWidth = dpi(44)
                setPadding(dpi(12), 0, dpi(12), 0)
                background = ContextCompat.getDrawable(this@PlayerActivity, R.drawable.bg_pill)
            }
            pinnedValueViews.add(action to pill)
            view = pill
        } else {
            view = ImageButton(this).apply {
                setImageResource(action.iconRes)
                imageTintList = ColorStateList.valueOf(Color.WHITE)
                background = selectableBackground(borderless = true)
            }
        }
        view.contentDescription = getString(action.labelRes)
        view.setOnClickListener {
            closeMenu()
            action.run()
        }
        view.setOnLongClickListener {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            togglePin(action)   // long-press a pinned button to unpin it
            true
        }
        return view
    }

    /** Full rebuild, used once at start-up. Later changes add or remove single buttons. */
    private fun refreshPinned() {
        b.pinnedStrip.removeAllViews()
        pinnedViews.clear()
        pinnedValueViews.clear()
        Prefs.pinnedActions(this).forEach { id ->
            val action = actions.firstOrNull { it.id == id } ?: return@forEach
            val view = createPinnedView(action)
            pinnedViews[id] = view
            b.pinnedStrip.addView(view, pinnedParams(action))
        }
    }

    private fun addPinned(action: PlayerAction) {
        if (pinnedViews.containsKey(action.id)) return
        TransitionManager.beginDelayedTransition(b.pinnedStrip, AutoTransition().setDuration(240))
        val view = createPinnedView(action)
        pinnedViews[action.id] = view
        b.pinnedStrip.addView(view, pinnedParams(action))
        view.scaleX = 0f
        view.scaleY = 0f
        view.animate().scaleX(1f).scaleY(1f).setDuration(300)
            .setInterpolator(OvershootInterpolator(2f)).start()
        // Make the new button visible if the strip is already scrolled.
        b.pinnedScroll.post { b.pinnedScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun removePinned(id: String) {
        val view = pinnedViews.remove(id) ?: return
        pinnedValueViews.removeAll { it.second === view }
        TransitionManager.beginDelayedTransition(b.pinnedStrip, AutoTransition().setDuration(220))
        b.pinnedStrip.removeView(view)
    }

    private fun updatePinnedValues() {
        pinnedValueViews.forEach { (action, view) -> view.text = action.value?.invoke() ?: "" }
    }

    // ---------- opening and closing ----------

    private fun spinGear() {
        b.btnMenu.animate().cancel()
        b.btnMenu.animate()
            .rotationBy(180f)
            .setDuration(350)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    private fun openMenu() {
        if (menuOpen) return
        menuOpen = true
        menuBackCallback.isEnabled = true
        mainHandler.removeCallbacks(hideControlsRunnable)

        // Always opens as the slim icon rail.
        setMenuExpanded(false, animate = false)
        buildMenuRows()
        spinGear()

        val panel = b.menuPanel
        val params = panel.layoutParams as FrameLayout.LayoutParams
        params.bottomMargin = b.controls.height + dpi(8)
        panel.layoutParams = params

        panel.animate().cancel()
        panel.visibility = View.VISIBLE
        panel.alpha = 0f
        panel.translationY = dpi(40).toFloat()
        panel.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(260)
            .setInterpolator(DecelerateInterpolator(1.8f))
            .start()

        // Icons rise in one after another.
        for (i in 0 until b.menuList.childCount) {
            val row = b.menuList.getChildAt(i)
            row.alpha = 0f
            row.translationY = dpi(16).toFloat()
            row.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(50L + i * 35L)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
    }

    private fun closeMenu() {
        if (!menuOpen) return
        menuOpen = false
        if (::menuBackCallback.isInitialized) menuBackCallback.isEnabled = false
        spinGear()
        val panel = b.menuPanel
        panel.animate().cancel()
        panel.animate()
            .alpha(0f)
            .translationY(dpi(32).toFloat())
            .setDuration(190)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction { if (!menuOpen) panel.visibility = View.GONE }
            .start()
        scheduleHideControls()
    }

    // ---------- controls ----------

    private fun setupControls() {
        b.btnPlay.setOnClickListener { togglePlay() }
        b.btnBack.setOnClickListener { finish() }
        b.btnClose.setOnClickListener { finish() }
        b.btnMenu.setOnClickListener {
            showControls()
            if (menuOpen) closeMenu() else openMenu()
        }
        b.btnPrev.setOnClickListener { if (hasPrev()) playIndex(playlistIndex - 1) }
        b.btnNext.setOnClickListener { if (hasNext()) playIndex(playlistIndex + 1) }
        setupMenu()
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
                if (menuOpen) closeMenu()
                else if (controlsVisible) hideControls() else showControls()
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
        closeMenu()
        fadeOut(b.controls)
        fadeOut(b.topBar)
        updateSubtitleOffset()
        mainHandler.removeCallbacks(hideControlsRunnable)
    }

    private fun scheduleHideControls() {
        mainHandler.removeCallbacks(hideControlsRunnable)
        if (controlsVisible && exo.playWhenReady && !menuOpen) {
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
        ttsUri = uri
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
