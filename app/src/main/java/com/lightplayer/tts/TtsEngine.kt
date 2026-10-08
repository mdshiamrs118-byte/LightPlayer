package com.lightplayer.tts

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.lightplayer.util.Prefs
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Thin blocking wrapper around Android's built-in TextToSpeech engine.
 * All methods must be called from a background thread; engine callbacks and
 * synthesis requests are marshalled to the main thread internally.
 */
class TtsEngine(context: Context, private val enginePackage: String?) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val appContext = context.applicationContext
    private val initLatch = CountDownLatch(1)

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var initError: String? = null
    @Volatile private var canceled = false
    @Volatile private var currentId: String? = null

    private val pending = ConcurrentHashMap<String, CountDownLatch>()
    private val results = ConcurrentHashMap<String, Boolean>()

    private val rate = Prefs.rate(appContext)
    private val pitch = Prefs.pitch(appContext)

    private val listener = TextToSpeech.OnInitListener { status ->
        if (status == TextToSpeech.SUCCESS) {
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { finish(utteranceId, true) }
                override fun onError(utteranceId: String?) { finish(utteranceId, false) }
                override fun onError(utteranceId: String?, errorCode: Int) { finish(utteranceId, false) }
            })
            tts?.setLanguage(Locale.getDefault())
            ready = true
        } else {
            initError = "engine unavailable"
        }
        initLatch.countDown()
    }

    /** Waits for engine initialisation. Blocking — background thread only. */
    fun prepare(timeoutMs: Long = 15_000): Boolean {
        mainHandler.post { startEngine() }
        val ok = initLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return ok && ready && tts != null
    }

    val error: String? get() = initError

    /** Synthesizes [text] to a WAV file. Blocking — background thread only. */
    fun synthesizeToFile(text: String, outFile: File, timeoutMs: Long = 25_000): Boolean {
        val t = tts ?: return false
        if (canceled) return false
        if (outFile.exists()) outFile.delete()
        outFile.parentFile?.mkdirs()

        val id = "utt-${System.nanoTime()}"
        val latch = CountDownLatch(1)
        pending[id] = latch
        currentId = id

        mainHandler.post {
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_RATE, rate)
                putFloat(TextToSpeech.Engine.KEY_PARAM_PITCH, pitch)
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
            }
            val res = t.synthesizeToFile(text, params, outFile, id)
            if (res != TextToSpeech.SUCCESS) finish(id, false)
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        var finished = false
        while (System.currentTimeMillis() < deadline) {
            if (latch.await(150, TimeUnit.MILLISECONDS)) { finished = true; break }
            if (canceled) break
        }
        currentId = null
        if (!finished) {
            pending.remove(id)
            mainHandler.post { t.stop() }
            return false
        }
        if (canceled) return false
        return results[id] == true && outFile.exists() && outFile.length() >= WavTools.HEADER_BYTES
    }

    fun cancel() {
        canceled = true
        currentId?.let { id -> pending.remove(id)?.countDown() }
        mainHandler.post { tts?.stop() }
    }

    fun shutdown() {
        canceled = true
        mainHandler.post { tts?.shutdown(); tts = null }
    }

    private fun startEngine() {
        if (tts != null) return
        tts = if (enginePackage.isNullOrBlank()) TextToSpeech(appContext, listener)
        else TextToSpeech(appContext, listener, enginePackage)
    }

    private fun finish(id: String?, ok: Boolean) {
        if (id == null) return
        results[id] = ok
        pending.remove(id)?.countDown()
    }
}