package com.lightplayer.ui.music

import android.content.ContentUris
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import com.lightplayer.util.MediaFiles
import java.io.File
import android.net.Uri
import android.provider.MediaStore
import com.lightplayer.tts.TtsIndex
import com.lightplayer.tts.TtsNaming

data class TtsTrack(
    val uri: Uri,
    val displayName: String,
    val videoTitle: String,
    val subtitleName: String,
    val dateAdded: Long,
    val size: Long,
    val durationMs: Long
) {
    /** What to call this audio: the subtitle's own name (the video is shown as a second line). */
    val title: String
        get() = if (subtitleName.isNotBlank()) TtsNaming.base(subtitleName) else videoTitle
}

object TtsTrackRepository {

    fun load(context: Context): List<TtsTrack> {
        val appCtx = context.applicationContext
        val index = TtsIndex(appCtx)
        val out = ArrayList<TtsTrack>()
        val stalePaths = ArrayList<String>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.RELATIVE_PATH
        )
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
        val args = arrayOf("%SubTTS%")

        appCtx.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val iDate = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val iRel = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)

            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val name = c.getString(iName) ?: continue
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

                // The media library can still list a file that was deleted with a file manager.
                if (!MediaFiles.exists(appCtx, uri)) {
                    index.remove(uri.toString())
                    val rel = c.getString(iRel) ?: ""
                    stalePaths.add(File(Environment.getExternalStorageDirectory(), rel + name).path)
                    try { appCtx.contentResolver.delete(uri, null, null) } catch (_: Throwable) {}
                    continue
                }

                val date = c.getLong(iDate)
                val size = c.getLong(iSize)
                var duration = c.getLong(iDur)

                val entry = index.findByUri(uri.toString())
                var videoTitle = entry?.videoTitle.orEmpty()
                var subtitleName = entry?.subtitleName.orEmpty()

                if (subtitleName.isEmpty()) {
                    // No record: read the name. Older files are "<video> ~ <subtitle> ~ <code>.wav",
                    // newer ones are simply "<subtitle name>[ [code]].wav".
                    val parsed = parseDisplayName(name)
                    if (parsed.second.isNotEmpty()) {
                        videoTitle = parsed.first
                        subtitleName = parsed.second
                    } else {
                        subtitleName = TtsNaming.base(name)
                    }
                }
                if (videoTitle.isEmpty()) videoTitle = TtsNaming.base(subtitleName.ifEmpty { name })
                if (duration <= 0) duration = entry?.durationMs ?: 0L

                out.add(
                    TtsTrack(
                        uri = uri,
                        displayName = name,
                        videoTitle = videoTitle,
                        subtitleName = subtitleName,
                        dateAdded = date,
                        size = size,
                        durationMs = duration
                    )
                )
            }
        }
        // Ask Android to refresh those entries so they disappear for good.
        if (stalePaths.isNotEmpty()) {
            try {
                MediaScannerConnection.scanFile(appCtx, stalePaths.toTypedArray(), null, null)
            } catch (_: Throwable) {
            }
        }
        return out
    }

    /** "<video> ~ <subtitle> ~ <key8>.wav" fallback when the index is gone. */
    private fun parseDisplayName(displayName: String): Pair<String, String> {
        val base = displayName.removeSuffix(".wav").removeSuffix(".WAV")
        val parts = base.split(" ~ ")
        return if (parts.size >= 3) parts[0] to parts[1] else "" to ""
    }
}