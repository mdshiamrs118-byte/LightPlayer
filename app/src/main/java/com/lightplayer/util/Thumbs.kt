package com.lightplayer.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/** Tiny in-memory video-thumbnail loader — no image library needed. */
object Thumbs {

    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(context: Context, uri: Uri, imageView: ImageView, placeholder: Int) {
        imageView.setImageResource(placeholder)
        imageView.tag = uri
        cache.get(uri.toString())?.let {
            imageView.setImageBitmap(it)
            return
        }
        val appCtx = context.applicationContext
        executor.execute {
            var bmp: Bitmap? = null
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(appCtx, uri)
                bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                retriever.release()
            } catch (t: Throwable) {
                bmp = null
            }
            if (bmp == null) bmp = junkSkippingThumb(appCtx, uri)
            val result = bmp ?: return@execute
            cache.put(uri.toString(), result)
            mainHandler.post {
                if (imageView.tag == uri) imageView.setImageBitmap(result)
            }
        }
    }

    /** Thumbnail for transport streams hidden behind a fake PNG header. */
    private fun junkSkippingThumb(ctx: Context, uri: Uri): Bitmap? {
        return try {
            val skip = TsCleaner.probeStart(ctx, uri)
            if (skip <= 0L) return null
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return null
            val channel = FileInputStream(pfd.fileDescriptor).channel
            val source = object : MediaDataSource() {
                override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                    if (size <= 0) return 0
                    val n = channel.read(ByteBuffer.wrap(buffer, offset, size), position + skip)
                    return if (n <= 0) -1 else n
                }
                override fun getSize(): Long = channel.size() - skip
                override fun close() {
                    try { channel.close() } catch (_: Throwable) {}
                    try { pfd.close() } catch (_: Throwable) {}
                }
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(source)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
                source.close()
            }
        } catch (t: Throwable) {
            null
        }
    }
}
