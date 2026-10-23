package com.lightplayer.ui.music

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.lightplayer.subtitle.Cue
import com.lightplayer.subtitle.SubtitleParser
import com.lightplayer.tts.TtsCache
import com.lightplayer.tts.TtsGenerator
import com.lightplayer.tts.TtsIndex
import com.lightplayer.util.SubtitleReader

/**
 * Converts several subtitle files to audio, one after another. Subtitles that already have
 * audio (made earlier here, in the player, or earlier in the same batch) are reused, not redone.
 * All callbacks arrive on the main thread.
 */
class BatchTtsConverter(
    context: Context,
    private val uris: List<Uri>,
    private val listener: Listener
) {

    enum class Status { CONVERTED, REUSED, FAILED }

    data class Result(val name: String, val status: Status, val detail: String = "")

    interface Listener {
        fun onFileStart(index: Int, total: Int, name: String)
        fun onCueProgress(done: Int, total: Int, preparing: Boolean)
        fun onFinished(results: List<Result>, canceled: Boolean)
    }

    private sealed class Prep {
        class Reuse(val name: String) : Prep()
        class Fail(val name: String, val why: String) : Prep()
        class Generate(val name: String, val cues: List<Cue>, val key: String) : Prep()
    }

    private val appCtx = context.applicationContext
    private val generator = TtsGenerator(appCtx)
    private val handler = Handler(Looper.getMainLooper())
    private val results = ArrayList<Result>()
    private var index = -1
    private var canceled = false
    private var finished = false

    fun start() = next()

    fun cancel() {
        if (finished || canceled) return
        canceled = true
        generator.cancel()          // a cancelled generation reports nothing, so finish here
        handler.post { finish() }
    }

    private fun next() {
        if (finished) return
        index++
        if (index >= uris.size) { finish(); return }
        val uri = uris[index]
        Thread {
            val prep = prepare(uri)
            handler.post { if (!finished) handle(prep) }
        }.apply { isDaemon = true; start() }
    }

    private fun prepare(uri: Uri): Prep {
        val name = SubtitleReader.displayName(appCtx, uri)
        val text = SubtitleReader.readText(appCtx, uri)
            ?: return Prep.Fail(name, "couldn't read the file (or it's over 8 MB)")
        val cues = try {
            SubtitleParser.parse(name, text).cues
        } catch (t: Throwable) {
            return Prep.Fail(name, "not a valid subtitle file")
        }
        if (cues.none { it.text.isNotBlank() }) return Prep.Fail(name, "no subtitle lines found")

        val existing = TtsCache.find(appCtx, TtsIndex(appCtx), name, cues)
        if (existing != null) return Prep.Reuse(name)

        return Prep.Generate(name, cues, TtsCache.contentKey(cues))
    }

    private fun handle(prep: Prep) {
        when (prep) {
            is Prep.Fail -> {
                listener.onFileStart(index, uris.size, prep.name)
                results.add(Result(prep.name, Status.FAILED, prep.why))
                next()
            }
            is Prep.Reuse -> {
                listener.onFileStart(index, uris.size, prep.name)
                results.add(Result(prep.name, Status.REUSED))
                next()
            }
            is Prep.Generate -> {
                listener.onFileStart(index, uris.size, prep.name)
                val title = prep.name.substringBeforeLast('.', prep.name)
                generator.generate(
                    prep.cues,
                    TtsGenerator.Meta(
                        key = prep.key,
                        videoTitle = title,
                        subtitleName = prep.name,
                        videoDurationMs = 0L,
                        contentKey = prep.key
                    ),
                    object : TtsGenerator.Listener {
                        override fun onProgress(done: Int, total: Int, preparing: Boolean) {
                            if (!finished) listener.onCueProgress(done, total, preparing)
                        }

                        override fun onDone(uri: Uri, durationMs: Long) {
                            if (finished) return
                            results.add(Result(prep.name, Status.CONVERTED))
                            next()
                        }

                        override fun onError(message: String) {
                            if (finished) return
                            results.add(Result(prep.name, Status.FAILED, message))
                            next()
                        }
                    }
                )
            }
        }
    }

    private fun finish() {
        if (finished) return
        finished = true
        listener.onFinished(ArrayList(results), canceled)
    }
}
