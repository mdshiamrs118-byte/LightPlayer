package com.lightplayer.ui.video

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val title: String,
    val dateAdded: Long,
    val size: Long,
    val durationMs: Long
)

data class FolderItem(
    val id: String,
    val name: String,
    val path: String,
    val latestDate: Long,
    val videos: List<VideoItem>
)

object VideoRepository {

    private class Bucket(
        val id: String,
        val name: String,
        val path: String,
        val videos: MutableList<VideoItem> = ArrayList(),
        var latestDate: Long = 0L
    )

    fun scan(context: Context): List<FolderItem> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH
        )

        val buckets = LinkedHashMap<String, Bucket>()

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            MediaStore.Video.Media.DATE_ADDED + " DESC"
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val iDate = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val iDur = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val iBucketId = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val iBucketName = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val iRel = c.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)

            while (c.moveToNext()) {
                val id = c.getLong(iId)
                val title = c.getString(iName) ?: "video"
                val date = c.getLong(iDate)
                val size = c.getLong(iSize)
                val duration = c.getLong(iDur)
                val bucketId: String? = c.getString(iBucketId)
                val bucketName: String? = c.getString(iBucketName)
                val relPath: String? = c.getString(iRel)

                val key = bucketId ?: (relPath ?: "root")
                val bucket = buckets.getOrPut(key) {
                    Bucket(
                        id = key,
                        name = bucketName
                            ?: relPath?.trimEnd('/')?.substringAfterLast('/')
                            ?: "Internal storage",
                        path = relPath ?: "/"
                    )
                }
                bucket.videos.add(
                    VideoItem(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        title = title,
                        dateAdded = date,
                        size = size,
                        durationMs = duration
                    )
                )
                if (date > bucket.latestDate) bucket.latestDate = date
            }
        }

        return buckets.values
            .map { b ->
                b.videos.sortBy { it.title.lowercase() }
                FolderItem(b.id, b.name, b.path, b.latestDate, b.videos)
            }
            .sortedBy { it.name.lowercase() }
    }
}