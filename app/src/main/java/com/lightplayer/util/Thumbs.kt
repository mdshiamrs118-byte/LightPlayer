package com.lightplayer.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
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
            val result = bmp ?: return@execute
            cache.put(uri.toString(), result)
            mainHandler.post {
                if (imageView.tag == uri) imageView.setImageBitmap(result)
            }
        }
    }
}