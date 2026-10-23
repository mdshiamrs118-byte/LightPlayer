package com.lightplayer.player

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.lightplayer.util.TsCleaner
import java.io.FileInputStream
import java.io.IOException
import java.nio.channels.FileChannel

/** ExoPlayer data source that serves a file with its junk (fake PNG headers) cut out. */
class TsCleanDataSource(
    private val context: Context,
    private val layout: TsCleaner.Layout
) : DataSource {

    private var pfd: ParcelFileDescriptor? = null
    private var channel: FileChannel? = null
    private var currentUri: Uri? = null
    private var pos = 0L
    private var remaining = 0L

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun open(dataSpec: DataSpec): Long {
        currentUri = dataSpec.uri
        val p = context.contentResolver.openFileDescriptor(dataSpec.uri, "r")
            ?: throw IOException("Cannot open ${dataSpec.uri}")
        pfd = p
        channel = FileInputStream(p.fileDescriptor).channel
        pos = dataSpec.position
        if (pos > layout.total) throw IOException("Position out of range")
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            minOf(dataSpec.length, layout.total - pos)
        } else {
            layout.total - pos
        }
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0L) return C.RESULT_END_OF_INPUT
        val ch = channel ?: return C.RESULT_END_OF_INPUT
        val n = layout.readAt(ch, pos, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        pos += n
        remaining -= n
        return n
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        try { channel?.close() } catch (_: Throwable) {}
        try { pfd?.close() } catch (_: Throwable) {}
        channel = null
        pfd = null
        currentUri = null
    }
}
