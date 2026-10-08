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
    val fileName: String
)

/** Persistent local index of every generated TTS track (cache lookups + metadata). */
class TtsIndex(context: Context) {

    private val file = File(context.applicationContext.filesDir, "tts_index.json")
    private val entries = ArrayList<TtsEntry>()

    init { load() }

    @Synchronized fun all(): List<TtsEntry> = ArrayList(entries)

    @Synchronized fun findByKey(key: String): TtsEntry? =
        entries.firstOrNull { it.key == key }

    @Synchronized fun findByUri(uri: String): TtsEntry? =
        entries.firstOrNull { it.uri == uri }

    @Synchronized fun add(entry: TtsEntry) {
        entries.removeAll { it.key == entry.key || it.uri == entry.uri }
        entries.add(entry)
        save()
    }

    @Synchronized fun remove(uri: String) {
        entries.removeAll { it.uri == uri }
        save()
    }

    @Synchronized fun clear() {
        entries.clear()
        save()
    }

    private fun load() {
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
                        fileName = o.optString("fileName")
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
                    }
                )
            }
            file.parentFile?.mkdirs()
            file.writeText(arr.toString())
        } catch (t: Throwable) {
            // Non-fatal: index is a cache accelerator, files remain the source of truth.
        }
    }
}