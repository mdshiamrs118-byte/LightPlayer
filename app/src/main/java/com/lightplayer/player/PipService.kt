package com.lightplayer.player

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import com.lightplayer.R
import com.lightplayer.util.PositionStore
import com.lightplayer.util.Prefs
import com.lightplayer.util.TsCleaner

/**
 * Runs the video behind the floating window. The player screen hands its state over (video,
 * position, speed, audio choices, playlist) and closes; "expand" hands it all back.
 */
class PipService : Service(), PipOverlay.Callbacks {

    /** Everything needed to carry on playing somewhere else. */
    class Session(
        val uri: Uri,
        val title: String,
        val durationMs: Long,
        val positionMs: Long,
        val playing: Boolean,
        val speed: Float,
        val volume: Float,
        val embeddedIndex: Int,
        val extraAudioUri: Uri?,
        val extraAudioName: String?,
        val ttsUri: Uri?,
        val ttsEnabled: Boolean,
        val ttsVolume: Float,
        val aspect: Float,
        val playlistUris: List<String>,
        val playlistTitles: List<String>,
        val playlistDurations: LongArray,
        val playlistIndex: Int
    )

    companion object {
        const val ACTION_START = "com.lightplayer.pip.START"
        const val ACTION_TOGGLE = "com.lightplayer.pip.TOGGLE"
        const val ACTION_CLOSE = "com.lightplayer.pip.CLOSE"
        const val ACTION_EXPAND = "com.lightplayer.pip.EXPAND"
        const val NOTIFICATION_ID = 43

        private const val X_URI = "uri"
        private const val X_TITLE = "title"
        private const val X_DURATION = "duration"
        private const val X_POSITION = "position"
        private const val X_PLAYING = "playing"
        private const val X_SPEED = "speed"
        private const val X_VOLUME = "volume"
        private const val X_EMBEDDED = "embedded"
        private const val X_EXTRA_URI = "extra_uri"
        private const val X_EXTRA_NAME = "extra_name"
        private const val X_TTS_URI = "tts_uri"
        private const val X_TTS_ENABLED = "tts_enabled"
        private const val X_TTS_VOLUME = "tts_volume"
        private const val X_ASPECT = "aspect"
        private const val X_LIST_URIS = "list_uris"
        private const val X_LIST_TITLES = "list_titles"
        private const val X_LIST_DURATIONS = "list_durations"
        private const val X_LIST_INDEX = "list_index"

        fun start(ctx: Context, s: Session) {
            val intent = Intent(ctx, PipService::class.java)
                .setAction(ACTION_START)
                .putExtra(X_URI, s.uri.toString())
                .putExtra(X_TITLE, s.title)
                .putExtra(X_DURATION, s.durationMs)
                .putExtra(X_POSITION, s.positionMs)
                .putExtra(X_PLAYING, s.playing)
                .putExtra(X_SPEED, s.speed)
                .putExtra(X_VOLUME, s.volume)
                .putExtra(X_EMBEDDED, s.embeddedIndex)
                .putExtra(X_EXTRA_URI, s.extraAudioUri?.toString())
                .putExtra(X_EXTRA_NAME, s.extraAudioName)
                .putExtra(X_TTS_URI, s.ttsUri?.toString())
                .putExtra(X_TTS_ENABLED, s.ttsEnabled)
                .putExtra(X_TTS_VOLUME, s.ttsVolume)
                .putExtra(X_ASPECT, s.aspect)
                .putStringArrayListExtra(X_LIST_URIS, ArrayList(s.playlistUris))
                .putStringArrayListExtra(X_LIST_TITLES, ArrayList(s.playlistTitles))
                .putExtra(X_LIST_DURATIONS, s.playlistDurations)
                .putExtra(X_LIST_INDEX, s.playlistIndex)
            ContextCompat.startForegroundService(ctx, intent)
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, PipService::class.java)) } catch (_: Throwable) {}
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: PipOverlay? = null
    private var player: ExoPlayer? = null
    private lateinit var tts: SyncedAudio
    private lateinit var extra: SyncedAudio

    private var session: Session? = null
    private var currentUri: Uri? = null
    private var currentTitle = ""
    private var currentDuration = 0L
    private var playlistIndex = 0
    private var wantPlay = true
    private var volume = 1f
    private var extraActive = false
    private var pendingEmbedded = -1
    private var embedded: List<Pair<Tracks.Group, Int>> = emptyList()
    private var loadToken = 0
    private var lastSave = 0L
    private var closing = false
    private var subtitleOffset = 0L

    private val ticker = object : Runnable {
        override fun run() {
            val p = player ?: return
            val pos = p.currentPosition
            val dur = p.duration
            overlay?.setProgress(if (dur > 0) pos.toFloat() / dur else 0f)
            val playing = p.isPlaying
            val ttsPos = pos - subtitleOffset
            if (ttsPos < 0L) tts.sync(0L, false) else tts.sync(ttsPos, playing)
            extra.sync(pos, playing)
            val now = SystemClock.elapsedRealtime()
            if (playing && now - lastSave > 5_000L) {
                lastSave = now
                savePosition()
            }
            handler.postDelayed(this, 400)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = SyncedAudio(this, speech = true)
        extra = SyncedAudio(this, speech = false)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSession(intent)
            ACTION_TOGGLE -> togglePlay()
            ACTION_CLOSE -> closeAndStop()
            ACTION_EXPAND -> expand()
            else -> if (session == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------- starting ----------

    private fun startSession(intent: Intent) {
        // A foreground service must call startForeground quickly, even if it then stops.
        val title = intent.getStringExtra(X_TITLE) ?: getString(R.string.app_name)
        startForegroundCompat(buildNotification(title, intent.getBooleanExtra(X_PLAYING, true)))

        val uriText = intent.getStringExtra(X_URI)
        if (uriText == null || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        // Replacing a running session (e.g. a second video sent to the window).
        releaseEverything(save = true)
        closing = false

        val s = Session(
            uri = Uri.parse(uriText),
            title = title,
            durationMs = intent.getLongExtra(X_DURATION, 0L),
            positionMs = intent.getLongExtra(X_POSITION, 0L),
            playing = intent.getBooleanExtra(X_PLAYING, true),
            speed = intent.getFloatExtra(X_SPEED, 1f),
            volume = intent.getFloatExtra(X_VOLUME, 1f),
            embeddedIndex = intent.getIntExtra(X_EMBEDDED, -1),
            extraAudioUri = intent.getStringExtra(X_EXTRA_URI)?.let { Uri.parse(it) },
            extraAudioName = intent.getStringExtra(X_EXTRA_NAME),
            ttsUri = intent.getStringExtra(X_TTS_URI)?.let { Uri.parse(it) },
            ttsEnabled = intent.getBooleanExtra(X_TTS_ENABLED, true),
            ttsVolume = intent.getFloatExtra(X_TTS_VOLUME, 1f),
            aspect = intent.getFloatExtra(X_ASPECT, 0f),
            playlistUris = intent.getStringArrayListExtra(X_LIST_URIS) ?: arrayListOf(),
            playlistTitles = intent.getStringArrayListExtra(X_LIST_TITLES) ?: arrayListOf(),
            playlistDurations = intent.getLongArrayExtra(X_LIST_DURATIONS) ?: LongArray(0),
            playlistIndex = intent.getIntExtra(X_LIST_INDEX, 0)
        )
        session = s
        currentUri = s.uri
        subtitleOffset = Prefs.subtitleOffset(this, s.uri.toString())
        currentTitle = s.title
        currentDuration = s.durationMs
        playlistIndex = s.playlistIndex
        wantPlay = s.playing
        volume = s.volume
        extraActive = s.extraAudioUri != null
        pendingEmbedded = if (extraActive) -1 else s.embeddedIndex

        val window = PipOverlay(this, this)
        try {
            window.attach(s.aspect)
        } catch (t: Throwable) {
            Toast.makeText(this, R.string.pip_error, Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        overlay = window
        updatePlaylistButtons()

        val p = createPlayer()
        player = p
        p.setVideoTextureView(window.texture)
        p.setPlaybackSpeed(s.speed)
        p.volume = if (extraActive) 0f else volume

        // Speech track and added audio carry over and stay locked to the video.
        tts.speed = s.speed
        extra.speed = s.speed
        tts.volume = s.ttsVolume
        extra.volume = volume
        if (s.ttsUri != null) {
            tts.enabled = s.ttsEnabled
            tts.load(s.ttsUri, (s.positionMs - subtitleOffset).coerceAtLeast(0L), s.playing && s.ttsEnabled) { tts.enabled = false }
        }
        if (s.extraAudioUri != null) {
            extra.enabled = true
            extra.load(s.extraAudioUri, s.positionMs, s.playing) { extraActive = false; extra.enabled = false; p.volume = volume }
        }

        loadMedia(s.uri, s.positionMs)
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun createPlayer(): ExoPlayer {
        val factory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(this, factory).build()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        p.setHandleAudioBecomingNoisy(true)
        p.setWakeMode(C.WAKE_MODE_LOCAL)
        p.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width <= 0 || size.height <= 0) return
                var w = size.width * size.pixelWidthHeightRatio
                var h = size.height.toFloat()
                if (size.unappliedRotationDegrees == 90 || size.unappliedRotationDegrees == 270) {
                    val t = w; w = h; h = t
                }
                overlay?.setAspect(w / h)
            }

            override fun onTracksChanged(tracks: Tracks) {
                embedded = collectAudio(tracks)
                val index = pendingEmbedded
                if (index in embedded.indices) {
                    pendingEmbedded = -1
                    val (group, track) = embedded[index]
                    player?.let {
                        it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
                            .build()
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded()
                updateNotification()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                updateNotification()
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PipService,
                    getString(R.string.player_error_play_fmt, error.errorCodeName),
                    Toast.LENGTH_LONG
                ).show()
                closeAndStop()
            }
        })
        return p
    }

    private fun collectAudio(tracks: Tracks): List<Pair<Tracks.Group, Int>> {
        val out = ArrayList<Pair<Tracks.Group, Int>>()
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_AUDIO) continue
            for (i in 0 until group.length) {
                if (group.isTrackSupported(i)) out.add(group to i)
            }
        }
        return out
    }

    /** Loads a video; videos hidden behind fake image headers are cleaned up first. */
    private fun loadMedia(uri: Uri, startMs: Long) {
        val token = ++loadToken
        val appCtx = applicationContext
        Thread {
            val layout = try { TsCleaner.analyze(appCtx, uri) } catch (_: Throwable) { null }
            handler.post {
                val p = player ?: return@post
                if (token != loadToken) return@post
                if (layout != null) {
                    val factory = DataSource.Factory { TsCleanDataSource(appCtx, layout) }
                    val item = MediaItem.Builder().setUri(uri).setMimeType(MimeTypes.VIDEO_MP2T).build()
                    p.setMediaSource(
                        ProgressiveMediaSource.Factory(factory, DefaultExtractorsFactory()).createMediaSource(item),
                        startMs
                    )
                } else {
                    p.setMediaItem(MediaItem.fromUri(uri), startMs)
                }
                p.playWhenReady = wantPlay
                p.prepare()
            }
        }.apply { isDaemon = true; start() }
    }

    // ---------- controls ----------

    override fun onTogglePlay() = togglePlay()

    override fun onSeekBy(deltaMs: Long) {
        val p = player ?: return
        val dur = p.duration
        val target = (p.currentPosition + deltaMs)
            .coerceAtLeast(0L)
            .let { if (dur > 0) it.coerceAtMost(dur) else it }
        p.seekTo(target)
        tts.forceTo((target - subtitleOffset).coerceAtLeast(0L), p.playWhenReady && target - subtitleOffset >= 0L)
        extra.forceTo(target, p.playWhenReady)
    }

    override fun onClose() = closeAndStop()
    override fun onExpand() = expand()

    override fun onNext() {
        val s = session ?: return
        if (playlistIndex + 1 < s.playlistUris.size) playIndex(playlistIndex + 1)
    }

    /** Previous video, or back to the start when already on the first one. */
    override fun onPrevious() {
        if (playlistIndex > 0) {
            playIndex(playlistIndex - 1)
        } else {
            onSeekBy(-(player?.currentPosition ?: 0L))
        }
    }

    private fun togglePlay() {
        val p = player ?: return
        if (p.playbackState == Player.STATE_ENDED) {
            p.seekTo(0L)
            tts.forceTo(0L, true)
            extra.forceTo(0L, true)
            p.playWhenReady = true
        } else {
            p.playWhenReady = !p.playWhenReady
            if (!p.playWhenReady) {
                tts.pause()
                extra.pause()
            }
        }
        wantPlay = p.playWhenReady
        overlay?.flashPlayState(p.playWhenReady)
        updateNotification()
    }

    /** Video finished: carry on with the next one in the folder, if there is one. */
    private fun onEnded() {
        currentUri?.let { PositionStore.clear(this, it.toString()) }
        val s = session ?: return
        val next = playlistIndex + 1
        if (Prefs.autoNext(this) && next < s.playlistUris.size) {
            playIndex(next)
        } else {
            overlay?.showControls()
        }
    }

    /** Switches the window to another video from the playlist. */
    private fun playIndex(index: Int) {
        val s = session ?: return
        if (index !in s.playlistUris.indices || index >= s.playlistTitles.size) return

        savePosition()   // remember where we left the video we are leaving
        playlistIndex = index
        val uri = Uri.parse(s.playlistUris[index])
        currentUri = uri
        subtitleOffset = Prefs.subtitleOffset(this, uri.toString())
        currentTitle = s.playlistTitles[index]
        currentDuration = s.playlistDurations.getOrElse(index) { 0L }

        // Added audio and speech belonged to the previous video.
        tts.release(); tts.enabled = false
        extra.release(); extra.enabled = false
        extraActive = false
        player?.volume = volume
        player?.let {
            it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_AUDIO).build()
        }

        wantPlay = true
        val start = if (Prefs.resumeEnabled(this)) {
            ((PositionStore.get(this, uri.toString())?.pos ?: 0L) - 2_000L).coerceAtLeast(0L)
        } else 0L
        loadMedia(uri, start)
        updateNotification()
        updatePlaylistButtons()
        overlay?.showControls()
    }

    private fun updatePlaylistButtons() {
        val s = session ?: return
        overlay?.setPlaylist(s.playlistUris.size > 1, playlistIndex + 1 < s.playlistUris.size)
    }

    // ---------- leaving ----------

    private fun savePosition() {
        val p = player ?: return
        val uri = currentUri ?: return
        if (!Prefs.resumeEnabled(this) || p.playbackState == Player.STATE_IDLE) return
        val dur = if (p.duration > 0) p.duration else currentDuration
        PositionStore.save(this, uri.toString(), p.currentPosition, dur)
    }

    private fun releaseEverything(save: Boolean) {
        handler.removeCallbacks(ticker)
        loadToken++
        if (save) savePosition()
        try { player?.release() } catch (_: Throwable) {}
        player = null
        tts.release()
        extra.release()
    }

    private fun closeAndStop() {
        if (closing) return
        closing = true
        releaseEverything(save = true)
        val window = overlay
        overlay = null
        session = null
        val finish = {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (_: Throwable) {
            }
        }
        if (window != null) window.detach(animate = true) { finish() } else finish()
    }

    /** Back to the full player screen, carrying everything over. */
    private fun expand() {
        val s = session ?: return
        val p = player
        val uri = currentUri ?: return
        val position = p?.currentPosition ?: s.positionMs
        val playing = p != null && p.playWhenReady && p.playbackState != Player.STATE_ENDED
        val embeddedIndex = if (extraActive) -1
        else embedded.indexOfFirst { it.first.isTrackSelected(it.second) }

        val intent = Intent(this, PlayerActivity::class.java).apply {
            data = uri
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(PlayerActivity.EXTRA_TITLE, currentTitle)
            putExtra(PlayerActivity.EXTRA_DURATION, currentDuration)
            putExtra(PlayerActivity.EXTRA_RESUME_POSITION, position)
            putExtra(PlayerActivity.EXTRA_RESUME_PLAYING, playing)
            putExtra(PlayerActivity.EXTRA_EMBEDDED_INDEX, embeddedIndex)
            if (extraActive && s.extraAudioUri != null) {
                putExtra(PlayerActivity.EXTRA_AUDIO_URI, s.extraAudioUri.toString())
                putExtra(PlayerActivity.EXTRA_AUDIO_NAME, s.extraAudioName)
            }
            if (s.ttsUri != null && tts.isLoaded) {
                putExtra(PlayerActivity.EXTRA_TTS_URI, s.ttsUri.toString())
                putExtra(PlayerActivity.EXTRA_TTS_ENABLED, tts.enabled)
            }
            if (s.playlistUris.size > 1) {
                putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_URIS, ArrayList(s.playlistUris))
                putStringArrayListExtra(PlayerActivity.EXTRA_PLAYLIST_TITLES, ArrayList(s.playlistTitles))
                putExtra(PlayerActivity.EXTRA_PLAYLIST_DURATIONS, s.playlistDurations)
                putExtra(PlayerActivity.EXTRA_PLAYLIST_INDEX, playlistIndex)
            }
        }
        try {
            startActivity(intent)
        } catch (_: Throwable) {
        }
        closeAndStop()
    }

    // ---------- notification ----------

    private fun serviceIntent(action: String, code: Int): PendingIntent = PendingIntent.getService(
        this, code,
        Intent(this, PipService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun buildNotification(title: String, playing: Boolean): Notification {
        PlaybackService.ensureChannel(this)
        return NotificationCompat.Builder(this, PlaybackService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif_music)
            .setContentTitle(title)
            .setContentText(getString(R.string.pip_notif_text))
            .setContentIntent(serviceIntent(ACTION_EXPAND, 3))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(
                NotificationCompat.Action.Builder(
                    if (playing) R.drawable.ic_notif_pause else R.drawable.ic_notif_play,
                    getString(if (playing) R.string.notif_pause else R.string.notif_play),
                    serviceIntent(ACTION_TOGGLE, 4)
                ).build()
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notif_close,
                    getString(R.string.notif_close),
                    serviceIntent(ACTION_CLOSE, 5)
                ).build()
            )
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun updateNotification() {
        if (session == null || closing) return
        val playing = player?.let { it.playWhenReady && it.playbackState != Player.STATE_ENDED } ?: false
        try {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(currentTitle, playing))
        } catch (_: Throwable) {
        }
    }

    // ---------- lifecycle ----------

    /** Screen rotated: keep the window on screen. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.clampPosition()
    }

    override fun onDestroy() {
        releaseEverything(save = true)
        overlay?.detach(animate = false) {}
        overlay = null
        session = null
        super.onDestroy()
    }
}
