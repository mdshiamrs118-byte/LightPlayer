package com.lightplayer.tts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TtsEntry(
    val key: String,
    val uri: String,
    val videoTitle: String,
    val subtitleName: String,
    val createdAt: Long,
    val durationMs: Long,
    val sizeBytes: Long,
    val fileName: String,
    /** Hash of the subtitle's text + timing; lets any video/subtitle file with the same lines reuse this audio. */
    val contentKey: String = ""
)

/** Persistent local index of every generated TTS track (cache lookups + metadata). */
class TtsIndex(context: Context) {

    private val file = File(context.applicationContext.filesDir, "tts_index.json")

    // Every TtsIndex(...) instance shares one list, so a record added by the generator is seen
    // at once by the player, and one instance can never overwrite another's newer records.
    private companion object {
        val LOCK = Any()
        var shared: ArrayList<TtsEntry>? = null
    }

    private val entries: ArrayList<TtsEntry>
        get() = synchronized(LOCK) {
            shared ?: ArrayList<TtsEntry>().also { shared = it; load(it) }
        }

    fun all(): List<TtsEntry> = synchronized(LOCK) { ArrayList(entries) }

    fun findByKey(key: String): TtsEntry? = synchronized(LOCK) {
        entries.firstOrNull { it.key == key }
    }

    fun findByUri(uri: String): TtsEntry? = synchronized(LOCK) {
        entries.firstOrNull { it.uri == uri }
    }

    fun add(entry: TtsEntry) = synchronized(LOCK) {
        entries.removeAll { it.key == entry.key || it.uri == entry.uri }
        entries.add(entry)
        save()
    }

    fun remove(uri: String) = synchronized(LOCK) {
        entries.removeAll { it.uri == uri }
        save()
    }

    fun clear() = synchronized(LOCK) {
        entries.clear()
        save()
    }

    private fun load(entries: ArrayList<TtsEntry>) {
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries.add(
                    TtsEntry(
                        key = o.optString("key"),
                        uri = o.optString("uri"),
                        videoTitle = o.optString("videoTitle"),
                        subtitleName = o.optString("subtitleName"),
                        createdAt = o.optLong("createdAt"),
                        durationMs = o.optLong("durationMs"),
                        sizeBytes = o.optLong("sizeBytes"),
                        fileName = o.optString("fileName"),
                        contentKey = o.optString("contentKey")
                    )
                )
            }
        } catch (t: Throwable) {
            // Corrupt index: start fresh; files still show via filename parsing.
            entries.clear()
        }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            for (e in entries) {
                arr.put(
                    JSONObject().apply {
                        put("key", e.key)
                        put("uri", e.uri)
                        put("videoTitle", e.videoTitle)
                        put("subtitleName", e.subtitleName)
                        put("createdAt", e.createdAt)
                        put("durationMs", e.durationMs)
                        put("sizeBytes", e.sizeBytes)
                        put("fileName", e.fileName)
                        put("contentKey", e.contentKey)
                    }
                )
            }
            file.parentFile?.mkdirs()
            // Write to a temporary file first so a crash can never leave a half-written index.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(arr.toString())
                tmp.delete()
            }
        } catch (t: Throwable) {
            // Non-fatal: index is a cache accelerator, files remain the source of truth.
        }
    }
}