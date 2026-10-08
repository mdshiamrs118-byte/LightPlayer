package com.lightplayer.tts

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.lightplayer.subtitle.Cue
import com.lightplayer.util.Prefs
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Converts a subtitle cue list into one timestamp-aligned PCM WAV track using
 * the built-in TTS engine, then publishes it to Music/SubTTS via MediaStore so
 * it shows up in the app's Music tab (and every other music app).
 *
 * Timeline layout: silence from 0 → first cue, then synthesized speech at each
 * cue's start timestamp, silence through the gaps. Playback is a plain
 * MediaPlayer seeked to the video position, so A/V sync is exact by design.
 */
class TtsGenerator(private val context: Context) {

    data class Meta(
        val key: String,
        val videoTitle: String,
        val subtitleName: String,
        val videoDurationMs: Long
    )

    interface Listener {
        fun onProgress(done: Int, total: Int, preparing: Boolean)
        fun onDone(uri: Uri, durationMs: Long)
        fun onError(message: String)
    }

    companion object {
        fun buildKey(parts: List<String>): String {
            val md = MessageDigest.getInstance("SHA-256")
            for (p in parts) {
                md.update(p.toByteArray(Charsets.UTF_8))
                md.update('|'.code.toByte())
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var canceled = false
    @Volatile private var engine: TtsEngine? = null

    fun cancel() {
        canceled = true
        engine?.cancel()
    }

    fun generate(cues: List<Cue>, meta: Meta, listener: Listener) {
        canceled = false
        Thread {
            try {
                generateBlocking(cues, meta, listener)
            } catch (t: Throwable) {
                error(listener, t.message ?: "Unexpected error")
            } finally {
                engine?.shutdown()
                engine = null
            }
        }.apply {
            name = "tts-generator"
            isDaemon = true
            start()
        }
    }

    private fun generateBlocking(cues: List<Cue>, meta: Meta, listener: Listener) {
        progress(listener, 0, 0, preparing = true)

        val eng = TtsEngine(context, Prefs.engine(context)).also { engine = it }
        if (!eng.prepare()) {
            error(listener, eng.error ?: "Text-to-speech engine not available")
            return
        }
        if (canceled) return

        val spoken = cues.filter { it.text.isNotBlank() }
        if (spoken.isEmpty()) {
            error(listener, "No speakable text in the subtitle file")
            return
        }

        val tmpDir = File(context.cacheDir, "tts").apply { mkdirs() }
        val tmpCue = File(tmpDir, "cue.wav")
        val outFile = File(tmpDir, "${meta.key}.wav")
        if (outFile.exists()) outFile.delete()

        var header: WavHeader? = null
        var lastMs = 0L
        var done = 0

        RandomAccessFile(outFile, "rw").use { raf ->
            for (cue in spoken) {
                if (canceled) return

                if (header == null) {
                    // First cue: synthesize first to learn the engine's sample rate,
                    // then write the header and backfill any leading silence.
                    if (!eng.synthesizeToFile(cue.text, tmpCue)) {
                        if (canceled) return
                        error(listener, "Speech synthesis failed")
                        return
                    }
                    val parsed = WavTools.parse(tmpCue) ?: run {
                        error(listener, "Could not read synthesized audio")
                        return
                    }
                    tmpCue.delete()
                    header = parsed.header
                    WavTools.writeHeader(raf, parsed.header, 0)
                    raf.seek(WavTools.HEADER_BYTES.toLong())
                    if (cue.startMs > 0) {
                        writeSilence(raf, parsed.header.msToBytes(cue.startMs))
                        lastMs = cue.startMs
                    }
                    raf.write(parsed.pcm)
                    lastMs = maxOf(lastMs + parsed.header.bytesToMs(parsed.pcm.size.toLong()), cue.startMs)
                } else {
                    val h = header!!
                    if (cue.startMs > lastMs) {
                        writeSilence(raf, h.msToBytes(cue.startMs - lastMs))
                        lastMs = cue.startMs
                    }
                    if (!eng.synthesizeToFile(cue.text, tmpCue)) {
                        if (canceled) return
                        error(listener, "Speech synthesis failed")
                        return
                    }
                    val parsed = WavTools.parse(tmpCue) ?: run {
                        error(listener, "Could not read synthesized audio")
                        return
                    }
                    tmpCue.delete()
                    raf.write(parsed.pcm)
                    lastMs = maxOf(lastMs + h.bytesToMs(parsed.pcm.size.toLong()), cue.startMs)
                }

                done++
                progress(listener, done, spoken.size, preparing = false)
            }

            if (canceled) return

            // Small tail so the last cue isn't clipped.
            val h = header!!
            val tail = lastMs + 400
            if (tail > lastMs) writeSilence(raf, h.msToBytes(tail - lastMs))

            val dataBytes = raf.filePointer - WavTools.HEADER_BYTES
            WavTools.writeHeader(raf, h, dataBytes)
        }

        tmpCue.delete()
        if (canceled) { outFile.delete(); return }

        val durationMs = header!!.bytesToMs(outFile.length() - WavTools.HEADER_BYTES)
        val sizeBytes = outFile.length()
        val fileName = "${sanitize(meta.videoTitle)} ~ ${sanitize(meta.subtitleName)} ~ ${meta.key.take(8)}.wav"

        val uri = publish(outFile, fileName)
        if (uri == null) {
            outFile.delete()
            error(listener, "Could not save audio to the Music folder")
            return
        }

        TtsIndex(context).add(
            TtsEntry(
                key = meta.key,
                uri = uri.toString(),
                videoTitle = meta.videoTitle,
                subtitleName = meta.subtitleName,
                createdAt = System.currentTimeMillis(),
                durationMs = durationMs,
                sizeBytes = sizeBytes,
                fileName = fileName
            )
        )
        outFile.delete()

        mainHandler.post { listener.onDone(uri, durationMs) }
    }

    /** Streams the finished WAV into Music/SubTTS through MediaStore (scoped storage safe). */
    private fun publish(source: File, displayName: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/x-wav")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/SubTTS")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            val out = resolver.openOutputStream(uri)
            if (out == null) {
                resolver.delete(uri, null, null)
                return null
            }
            out.use { o -> FileInputStream(source).use { it.copyTo(o, 64 * 1024) } }
            if (Build.VERSION.SDK_INT >= 29) {
                val doneValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }
                resolver.update(uri, doneValues, null, null)
            }
            uri
        } catch (t: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
            null
        }
    }

    private fun writeSilence(raf: RandomAccessFile, bytes: Long) {
        if (bytes <= 0) return
        val buf = ByteArray(8 * 1024)
        var remaining = bytes
        while (remaining > 0) {
            val n = minOf(remaining, buf.size.toLong()).toInt()
            raf.write(buf, 0, n)
            remaining -= n
        }
    }

    private fun sanitize(name: String): String {
        var t = name.replace(Regex("[/\\\\:*?\"<>|]"), "_").replace(" ~ ", "-").trim()
        if (t.isEmpty()) t = "audio"
        if (t.length > 60) t = t.substring(0, 60)
        return t
    }

    private fun progress(l: Listener, done: Int, total: Int, preparing: Boolean) {
        mainHandler.post { l.onProgress(done, total, preparing) }
    }

    private fun error(l: Listener, message: String) {
        mainHandler.post { l.onError(message) }
    }
}