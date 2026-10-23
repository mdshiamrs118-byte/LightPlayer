package com.lightplayer.util

import android.content.Context
import android.net.Uri
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Some streaming sites hide MPEG-TS (HLS) video behind a fake PNG header
 * (IHDR / IDAT / IEND ...), and when segments are joined the junk can repeat
 * in the middle of the file. Players sniff the start of the file, see "PNG"
 * and give up.
 *
 * This finds the real transport-stream packets (0x47 sync byte every 188 bytes)
 * and describes the file as a list of clean segments, so the junk can be skipped.
 */
object TsCleaner {

    private const val PACKET = 188
    private const val SYNC = 0x47
    private const val PROBE_BYTES = 64 * 1024
    private const val WINDOW = 1 shl 20

    /** Clean (junk-free) view of a file: parallel arrays, one entry per good segment. */
    class Layout(
        val physStart: LongArray,
        val virtStart: LongArray,
        val length: LongArray
    ) {
        val total: Long = if (length.isEmpty()) 0L else virtStart.last() + length.last()

        private fun segmentFor(virtPos: Long): Int {
            var lo = 0
            var hi = virtStart.size - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                when {
                    virtPos < virtStart[mid] -> hi = mid - 1
                    virtPos >= virtStart[mid] + length[mid] -> lo = mid + 1
                    else -> return mid
                }
            }
            return -1
        }

        /** Reads up to [size] clean bytes starting at [virtPos]; -1 at end of data. */
        fun readAt(channel: FileChannel, virtPos: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size <= 0) return 0
            val seg = segmentFor(virtPos)
            if (seg < 0) return -1
            val inSeg = virtPos - virtStart[seg]
            val n = minOf(size.toLong(), length[seg] - inSeg).toInt()
            val read = channel.read(ByteBuffer.wrap(buffer, offset, n), physStart[seg] + inSeg)
            return if (read <= 0) -1 else read
        }
    }

    private val cache = HashMap<String, Layout?>()

    /**
     * Returns a layout when [uri] is a transport stream hidden behind junk, or null when the
     * file can be played as-is (or isn't a transport stream at all). Slow for large files
     * (reads the whole file once), so call it off the main thread. Results are cached.
     */
    fun analyze(context: Context, uri: Uri): Layout? {
        val key = uri.toString()
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val result = try { doAnalyze(context, uri) } catch (t: Throwable) { null }
        synchronized(cache) { cache[key] = result }
        return result
    }

    /** Cheap version for thumbnails: offset of the first real packet, or 0 if no cleanup needed. */
    fun probeStart(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    val head = readHead(ch)
                    if (looksLikeKnownContainer(head)) 0L else findSyncRun(head, 0, head.size, head.size)
                        .let { if (it < 0) 0L else it.toLong() }
                }
            } ?: 0L
        } catch (t: Throwable) { 0L }
    }

    // ---------- internals ----------

    private fun doAnalyze(context: Context, uri: Uri): Layout? {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        pfd.use {
            FileInputStream(it.fileDescriptor).channel.use { ch ->
                val size = ch.size()
                val head = readHead(ch)
                if (looksLikeKnownContainer(head)) return null
                // Quick reject: no transport-stream packets near the start -> not our case.
                if (findSyncRun(head, 0, head.size, head.size) < 0) return null

                val reader = WindowReader(ch, size)
                val phys = ArrayList<Long>()
                val len = ArrayList<Long>()
                var pos = 0L
                while (pos + PACKET <= size) {
                    val start = reader.findSyncRun(pos) ?: break
                    var q = start
                    while (q + PACKET <= size && reader.get(q) == SYNC) q += PACKET
                    phys.add(start)
                    len.add(q - start)
                    pos = q
                }
                if (phys.isEmpty()) return null
                // Already one clean stream starting at byte 0 -> nothing to fix.
                if (phys.size == 1 && phys[0] == 0L) return null

                val virt = LongArray(phys.size)
                var acc = 0L
                for (i in phys.indices) { virt[i] = acc; acc += len[i] }
                return Layout(phys.toLongArray(), virt, len.toLongArray())
            }
        }
    }

    private fun readHead(ch: FileChannel): ByteArray {
        val buf = ByteBuffer.allocate(PROBE_BYTES)
        var total = 0
        while (buf.hasRemaining()) {
            val n = ch.read(buf, total.toLong())
            if (n <= 0) break
            total += n
        }
        return buf.array().copyOf(total)
    }

    /** Containers players already detect by themselves — never touch those. */
    private fun looksLikeKnownContainer(h: ByteArray): Boolean {
        if (h.size < 12) return true
        fun at(i: Int, s: String) = s.indices.all { h[i + it] == s[it].code.toByte() }
        if (at(4, "ftyp")) return true                         // mp4 / mov
        if (at(0, "RIFF") || at(0, "OggS") || at(0, "FLV")) return true
        if ((h[0].toInt() and 0xFF) == 0x1A && (h[1].toInt() and 0xFF) == 0x45) return true // mkv/webm
        return false
    }

    /** Position in [b] of the first byte that starts a run of >=5 spaced sync bytes, or -1. */
    private fun findSyncRun(b: ByteArray, from: Int, to: Int, limit: Int): Int {
        val need = 5
        var p = from
        while (p < to) {
            if ((b[p].toInt() and 0xFF) == SYNC) {
                var ok = true
                for (i in 1 until need) {
                    val idx = p + i * PACKET
                    if (idx >= limit || (b[idx].toInt() and 0xFF) != SYNC) { ok = false; break }
                }
                if (ok) return p
            }
            p++
        }
        return -1
    }

    /** Random-access byte reader with a sliding window, so full-file scans stay fast. */
    private class WindowReader(private val ch: FileChannel, private val size: Long) {
        private var base = -1L
        private var data = ByteArray(0)

        fun get(pos: Long): Int {
            if (pos < 0 || pos >= size) return -1
            if (base < 0 || pos < base || pos >= base + data.size) load(pos)
            return data[(pos - base).toInt()].toInt() and 0xFF
        }

        private fun load(pos: Long) {
            val n = minOf(WINDOW.toLong(), size - pos).toInt()
            val buf = ByteBuffer.allocate(n)
            var read = 0
            while (buf.hasRemaining()) {
                val r = ch.read(buf, pos + read)
                if (r <= 0) break
                read += r
            }
            base = pos
            data = buf.array().copyOf(read)
        }

        /** First position >= [from] starting a run of sync bytes (>=5 packets, or >=3 near EOF). */
        fun findSyncRun(from: Long): Long? {
            var p = from
            while (p + PACKET <= size) {
                if (get(p) == SYNC) {
                    val avail = ((size - p) / PACKET).toInt()
                    val need = minOf(5, avail)
                    if (need >= 3) {
                        var ok = true
                        for (i in 1 until need) {
                            if (get(p + i * PACKET.toLong()) != SYNC) { ok = false; break }
                        }
                        if (ok) return p
                    }
                }
                p++
            }
            return null
        }
    }
}
