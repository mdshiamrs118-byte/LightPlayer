package com.lightplayer.tts

import java.io.File
import java.io.RandomAccessFile

data class WavHeader(val channels: Int, val sampleRate: Int, val bitsPerSample: Int) {
    val blockAlign: Int get() = channels * bitsPerSample / 8
    val byteRate: Int get() = sampleRate * blockAlign
    fun msToBytes(ms: Long): Long = (ms * byteRate) / 1000L
    fun bytesToMs(bytes: Long): Long = if (byteRate <= 0) 0 else (bytes * 1000L) / byteRate
}

object WavTools {

    const val HEADER_BYTES = 44
    private const val MAX_PCM_BYTES = 64L * 1024 * 1024

    data class Parsed(val header: WavHeader, val pcm: ByteArray, val durationMs: Long)

    /** Writes/patches the canonical 44-byte PCM WAV header at offset 0. */
    fun writeHeader(raf: RandomAccessFile, h: WavHeader, dataBytes: Long) {
        val clamped = dataBytes.coerceIn(0L, 0x7FFFFF00L)
        raf.seek(0)
        raf.writeBytes("RIFF")
        writeIntLE(raf, 36L + clamped)
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        writeIntLE(raf, 16)
        writeShortLE(raf, 1)                 // PCM
        writeShortLE(raf, h.channels)
        writeIntLE(raf, h.sampleRate)
        writeIntLE(raf, h.byteRate)
        writeShortLE(raf, h.blockAlign)
        writeShortLE(raf, h.bitsPerSample)
        raf.writeBytes("data")
        writeIntLE(raf, clamped)
    }

    /** Parses a WAV produced by the TTS engine and returns its raw PCM payload. */
    fun parse(file: File): Parsed? {
        if (!file.exists() || file.length() < HEADER_BYTES) return null
        RandomAccessFile(file, "r").use { raf ->
            val tag = ByteArray(4)
            if (raf.read(tag) != 4 || String(tag) != "RIFF") return null
            raf.skipBytes(4)
            if (raf.read(tag) != 4 || String(tag) != "WAVE") return null

            var channels = 0
            var rate = 0
            var bits = 0
            var dataStart = -1L
            var dataSize = -1L

            while (raf.filePointer + 8 <= raf.length()) {
                if (raf.read(tag) != 4) break
                val id = String(tag)
                val size = readIntLE(raf).toLong() and 0xFFFFFFFFL
                when (id) {
                    "fmt " -> {
                        val start = raf.filePointer
                        if (start + 16 > raf.length()) return null
                        val format = readShortLE(raf)
                        channels = readShortLE(raf)
                        rate = readIntLE(raf)
                        raf.skipBytes(6)                       // byte rate + block align
                        bits = readShortLE(raf)
                        if (format == 0xFFFE && size >= 40) {  // WAVE_FORMAT_EXTENSIBLE
                            raf.skipBytes(8)                   // cbSize + validBits + channelMask
                            val subFormat = readShortLE(raf)
                            if (subFormat != 1) return null
                        } else if (format != 1) {
                            return null
                        }
                        raf.seek(start + size + (size % 2))
                    }
                    "data" -> {
                        dataStart = raf.filePointer
                        val available = raf.length() - dataStart
                        dataSize = if (size <= 0 || size > available) available else size
                        break
                    }
                    else -> raf.seek(raf.filePointer + size + (size % 2))
                }
            }

            if (dataStart < 0 || channels <= 0 || rate <= 0 || bits != 16) return null
            if (dataSize <= 0) return Parsed(WavHeader(channels, rate, bits), ByteArray(0), 0L)
            if (dataSize > MAX_PCM_BYTES) return null

            raf.seek(dataStart)
            val pcm = ByteArray(dataSize.toInt())
            raf.readFully(pcm)
            val header = WavHeader(channels, rate, bits)
            return Parsed(header, pcm, header.bytesToMs(pcm.size.toLong()))
        }
    }

    private fun writeIntLE(raf: RandomAccessFile, v: Long) {
        val b = ByteArray(4)
        b[0] = (v and 0xFF).toByte()
        b[1] = ((v shr 8) and 0xFF).toByte()
        b[2] = ((v shr 16) and 0xFF).toByte()
        b[3] = ((v shr 24) and 0xFF).toByte()
        raf.write(b)
    }

    private fun writeShortLE(raf: RandomAccessFile, v: Int) {
        raf.write(v and 0xFF)
        raf.write((v shr 8) and 0xFF)
    }

    private fun readIntLE(raf: RandomAccessFile): Int {
        val b0 = raf.read(); val b1 = raf.read(); val b2 = raf.read(); val b3 = raf.read()
        if (b3 < 0) return 0
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun readShortLE(raf: RandomAccessFile): Int {
        val b0 = raf.read(); val b1 = raf.read()
        if (b1 < 0) return 0
        return b0 or (b1 shl 8)
    }
}