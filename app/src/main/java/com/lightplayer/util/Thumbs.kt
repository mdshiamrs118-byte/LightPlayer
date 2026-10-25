package com.lightplayer.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.Size
import android.widget.ImageView
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * Video thumbnail loader, no image library needed.
 *
 * Fast path first: Android's own thumbnail cache. Fallbacks: grabbing a frame ourselves, and
 * videos hidden behind a fake image header. Results are kept in memory and on disk, so scrolling
 * the library is quick even after the app restarts.
 */
object Thumbs {

    private const val DISK_LIMIT_BYTES = 40L * 1024 * 1024

    private val executor = Executors.newFixedThreadPool(3)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /**
     * [onDuration] is called on the main thread if the video's length had to be worked out
     * here (the media library reported none); it is also stored in [DurationCache].
     */
    fun load(
        context: Context,
        uri: Uri,
        imageView: ImageView,
        placeholder: Int,
        onDuration: ((Long) -> Unit)? = null
    ) {
        imageView.setImageResource(placeholder)
        imageView.tag = uri
        val key = uri.toString()
        cache.get(key)?.let {
            imageView.setImageBitmap(it)
            return
        }
        val appCtx = context.applicationContext
        executor.execute {
            val bmp = diskGet(appCtx, key) ?: generate(appCtx, uri, onDuration) ?: return@execute
            cache.put(key, bmp)
            mainHandler.post {
                if (imageView.tag == uri) imageView.setImageBitmap(bmp)
            }
        }
    }

    /** Forget everything cached for a video (after it was deleted). */
    fun forget(context: Context, uri: Uri) {
        val key = uri.toString()
        cache.remove(key)
        try { diskFile(context, key).delete() } catch (_: Throwable) {}
    }

    // ---------- generating ----------

    private fun generate(ctx: Context, uri: Uri, onDuration: ((Long) -> Unit)?): Bitmap? {
        // 1. Android's media library thumbnail (already cached by the system).
        try {
            val t = ctx.contentResolver.loadThumbnail(uri, Size(480, 270), null)
            diskPut(ctx, uri.toString(), t)
            return t
        } catch (_: Throwable) {
        }

        // 2. Grab a frame ourselves.
        var bmp: Bitmap? = null
        try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(ctx, uri)
                bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
            }
        } catch (_: Throwable) {
            bmp = null
        }

        // 3. Transport streams hidden behind a fake PNG header.
        if (bmp == null) bmp = junkSkippingThumb(ctx, uri, onDuration)

        bmp?.let { diskPut(ctx, uri.toString(), it) }
        return bmp
    }

    private fun junkSkippingThumb(ctx: Context, uri: Uri, onDuration: ((Long) -> Unit)?): Bitmap? {
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
                val duration = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                if (duration > 0L) {
                    DurationCache.put(ctx, uri.toString(), duration)
                    onDuration?.let { cb -> mainHandler.post { cb(duration) } }
                }
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
                source.close()
            }
        } catch (_: Throwable) {
            null
        }
    }

    // ---------- disk cache ----------

    private fun diskDir(ctx: Context): File = File(ctx.cacheDir, "thumbs").also { it.mkdirs() }

    private fun diskFile(ctx: Context, key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(diskDir(ctx), "$name.jpg")
    }

    private fun diskGet(ctx: Context, key: String): Bitmap? = try {
        val f = diskFile(ctx, key)
        if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    } catch (_: Throwable) {
        null
    }

    private fun diskPut(ctx: Context, key: String, bmp: Bitmap) {
        try {
            val dir = diskDir(ctx)
            FileOutputStream(diskFile(ctx, key)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
            val files = dir.listFiles() ?: return
            var total = files.sumOf { it.length() }
            if (total > DISK_LIMIT_BYTES) {
                for (old in files.sortedBy { it.lastModified() }) {
                    if (total <= DISK_LIMIT_BYTES * 3 / 4) break
                    total -= old.length()
                    old.delete()
                }
            }
        } catch (_: Throwable) {
        }
    }
}
