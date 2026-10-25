package com.lightplayer.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lightplayer.R

/**
 * Keeps the app alive while a video plays with the screen off or the app in the background.
 * The player itself stays in [PlayerActivity]; this only owns the notification and the
 * foreground state.
 */
class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "background_playback"
        const val NOTIFICATION_ID = 42
        const val ACTION_TOGGLE = "com.lightplayer.action.TOGGLE"
        const val ACTION_CLOSE = "com.lightplayer.action.CLOSE"
        const val EXTRA_TITLE = "title"
        const val EXTRA_PLAYING = "playing"

        fun buildNotification(ctx: Context, title: String, playing: Boolean): Notification {
            ensureChannel(ctx)
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, PlayerActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            fun action(icon: Int, label: String, act: String, code: Int): NotificationCompat.Action {
                val pi = PendingIntent.getBroadcast(
                    ctx, code,
                    Intent(act).setPackage(ctx.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                return NotificationCompat.Action.Builder(icon, label, pi).build()
            }

            return NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notif_music)
                .setContentTitle(title)
                .setContentText(ctx.getString(if (playing) R.string.notif_playing else R.string.notif_paused))
                .setContentIntent(open)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .addAction(
                    action(
                        if (playing) R.drawable.ic_notif_pause else R.drawable.ic_notif_play,
                        ctx.getString(if (playing) R.string.notif_pause else R.string.notif_play),
                        ACTION_TOGGLE, 1
                    )
                )
                .addAction(action(R.drawable.ic_notif_close, ctx.getString(R.string.notif_close), ACTION_CLOSE, 2))
                .build()
        }

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        ctx.getString(R.string.notif_channel),
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, PlaybackService::class.java)) } catch (_: Throwable) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.app_name)
        val playing = intent?.getBooleanExtra(EXTRA_PLAYING, true) ?: true
        startForeground(
            NOTIFICATION_ID,
            buildNotification(this, title, playing),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
        return START_NOT_STICKY
    }
}
