package com.lightplayer.player

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs

/**
 * A secondary audio-only player that is kept locked to the video clock.
 * Used for the generated TTS track and for user-added external audio tracks.
 * Must only be used from the main thread.
 */
class SyncedAudio(context: Context, private val speech: Boolean) {

    private val appCtx = context.applicationContext
    private var player: ExoPlayer? = null
    private var lastSeekAt = 0L

    var enabled = false
    var volume = 1f
        set(value) {
            field = value
            player?.volume = value
        }

    var speed = 1f
        set(value) {
            field = value
            player?.setPlaybackSpeed(value)
        }

    val isLoaded: Boolean get() = player != null
    val isReady: Boolean
        get() = player?.let { it.playbackState == Player.STATE_READY || it.playbackState == Player.STATE_BUFFERING } == true

    fun load(uri: Uri, startMs: Long, playNow: Boolean, onError: () -> Unit) {
        release()
        val p = ExoPlayer.Builder(appCtx).build()
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(if (speech) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            /* handleAudioFocus = */ false
        )
        p.volume = volume
        p.setPlaybackSpeed(speed)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                onError()
            }
        })
        p.setMediaItem(MediaItem.fromUri(uri))
        p.seekTo(startMs.coerceAtLeast(0L))
        p.playWhenReady = playNow
        p.prepare()
        player = p
    }

    fun pause() {
        player?.playWhenReady = false
    }

    /** Called every tick: starts/stops the track and corrects drift against the video position. */
    fun sync(videoPos: Long, videoPlaying: Boolean) {
        val p = player ?: return
        if (p.playbackState == Player.STATE_IDLE) return

        if (!enabled || !videoPlaying) {
            if (p.playWhenReady) p.playWhenReady = false
            return
        }

        val dur = p.duration
        if (dur != C.TIME_UNSET && dur > 0 && dur < videoPos + 150) {
            // This track is shorter than the video; don't loop it.
            if (p.playWhenReady) p.playWhenReady = false
            return
        }

        val diff = p.currentPosition - videoPos
        val now = SystemClock.elapsedRealtime()
        if (abs(diff) > 350 && now - lastSeekAt > 700) {
            lastSeekAt = now
            p.seekTo(videoPos)
        }
        if (!p.playWhenReady) p.playWhenReady = true
    }

    fun forceTo(videoPos: Long, videoPlaying: Boolean) {
        val p = player ?: return
        lastSeekAt = SystemClock.elapsedRealtime()
        p.seekTo(videoPos.coerceAtLeast(0L))
        p.playWhenReady = enabled && videoPlaying
    }

    fun release() {
        try { player?.release() } catch (_: Throwable) {}
        player = null
    }
}
