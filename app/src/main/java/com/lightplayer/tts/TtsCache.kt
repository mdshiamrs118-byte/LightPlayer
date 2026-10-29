package com.lightplayer.tts

import android.content.Context
import android.net.Uri
import com.lightplayer.subtitle.Cue
import com.lightplayer.ui.music.TtsTrackRepository
import com.lightplayer.util.MediaFiles

/**
 * Finds audio that was already generated for a subtitle, so it is never made twice.
 *
 * The spoken audio depends only on the subtitle lines and their timing, so tracks are matched
 * by a hash of the subtitle content. Several independent checks are used, so a lost or
 * out-of-date record never causes the same audio to be generated again:
 *  1. the app's record, matched by content;
 *  2. the app's older records, matched by the subtitle's file name;
 *  3. the audio files themselves in Music/SubTTS, matched by name and length.
 */
object TtsCache {

    fun contentKey(cues: List<Cue>): String {
        val parts = ArrayList<String>(cues.size + 1)
        parts.add("subtitle-v1")
        for (c in cues) {
            if (c.text.isNotBlank()) parts.add("${c.startMs}:${c.endMs}:${c.text.trim()}")
        }
        return TtsGenerator.buildKey(parts)
    }

    /** Returns a still-existing track for this subtitle, or null. Does file I/O: not for the main thread. */
    fun find(context: Context, index: TtsIndex, subtitleName: String, cues: List<Cue>): TtsEntry? {
        val spoken = cues.filter { it.text.isNotBlank() }
        if (spoken.isEmpty()) return null
        val ck = contentKey(cues)
        val lastStart = spoken.maxOf { it.startMs }
        val lastEnd = spoken.maxOf { it.endMs }
        val wantedName = TtsNaming.comparable(subtitleName)

        // 1 + 2: the app's own records.
        for (e in index.all().sortedByDescending { it.createdAt }) {
            val exact = e.contentKey.isNotEmpty() && e.contentKey == ck
            val legacy = e.contentKey.isEmpty() &&
                TtsNaming.comparable(e.subtitleName) == wantedName &&
                e.durationMs >= lastStart + 300
            if (!exact && !legacy) continue

            if (!MediaFiles.exists(context, Uri.parse(e.uri))) {
                index.remove(e.uri)      // file was deleted outside the app
                continue
            }
            val upgraded = if (legacy) e.copy(contentKey = ck) else e
            if (legacy) index.add(upgraded)   // exact matching from now on
            return upgraded
        }

        // 3: audio files the records don't cover (records lost, or made by an older version).
        val recorded = index.all().associateBy { it.uri }
        for (t in TtsTrackRepository.load(context)) {
            val record = recorded[t.uri.toString()]
            // Known to be made from a different subtitle: not ours.
            if (record != null && record.contentKey.isNotEmpty() && record.contentKey != ck) continue

            val fileName = t.subtitleName.ifEmpty { t.displayName }
            if (TtsNaming.comparable(fileName) != wantedName) continue

            // A name like "English [a1b2c3d4].wav" carries the content code of its subtitle:
            // a different code means different lines, the same code is a certain match.
            val nameCode = TtsNaming.keyOf(t.displayName)
            if (nameCode != null && nameCode != ck.take(8)) continue
            if (nameCode == null) {
                // Plain name only: make sure the audio is not obviously from another file.
                val firstStart = spoken.minOf { it.startMs }
                if (t.durationMs in 1 until firstStart + 300) continue            // too short
                if (t.durationMs > lastEnd * 2 + 120_000) continue               // far too long
            }

            val adopted = TtsEntry(
                key = ck,
                uri = t.uri.toString(),
                videoTitle = t.videoTitle,
                subtitleName = subtitleName,
                createdAt = if (t.dateAdded > 0) t.dateAdded * 1000 else System.currentTimeMillis(),
                durationMs = t.durationMs,
                sizeBytes = t.size,
                fileName = t.displayName,
                contentKey = ck
            )
            index.add(adopted)
            return adopted
        }
        return null
    }
}
