package com.lightplayer.ui.video

import android.content.Context
import com.lightplayer.R
import com.lightplayer.util.DurationCache
import com.lightplayer.util.FavoritesStore
import com.lightplayer.util.NaturalOrder
import com.lightplayer.util.PositionStore

enum class SortMode { NAME, DATE, SIZE, DURATION }

/**
 * Turns the raw media-library scan into what the Videos tab shows: search results, the chosen
 * sort order, and the "Continue watching" and "Favorites" sections at the top.
 */
object LibraryBuilder {

    const val CONTINUE_ID = "__continue__"
    const val FAVORITES_ID = "__favorites__"
    private const val CONTINUE_LIMIT = 10

    fun isPseudo(id: String) = id.startsWith("__")

    fun effectiveDuration(ctx: Context, v: VideoItem): Long =
        if (v.durationMs > 0L) v.durationMs else DurationCache.get(ctx, v.uri.toString())

    private fun sortVideos(ctx: Context, videos: List<VideoItem>, sort: SortMode): List<VideoItem> = when (sort) {
        SortMode.NAME -> videos.sortedWith { a, b -> NaturalOrder.compare(a.title, b.title) }
        SortMode.DATE -> videos.sortedByDescending { it.dateAdded }
        SortMode.SIZE -> videos.sortedByDescending { it.size }
        SortMode.DURATION -> videos.sortedByDescending { effectiveDuration(ctx, it) }
    }

    fun build(ctx: Context, all: List<FolderItem>, query: String, sort: SortMode): List<FolderItem> {
        val q = query.trim().lowercase()
        val searching = q.isNotEmpty()

        val filtered = if (searching) {
            all.mapNotNull { folder ->
                val folderHit = folder.name.lowercase().contains(q)
                val hits = if (folderHit) folder.videos else folder.videos.filter { it.title.lowercase().contains(q) }
                if (hits.isEmpty()) null else folder.copy(videos = hits)
            }
        } else {
            all
        }

        val sorted = filtered.map { it.copy(videos = sortVideos(ctx, it.videos, sort)) }
        val folders = when (sort) {
            SortMode.NAME -> sorted.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
            SortMode.DATE -> sorted.sortedByDescending { it.latestDate }
            SortMode.SIZE -> sorted.sortedByDescending { f -> f.videos.sumOf { it.size } }
            SortMode.DURATION -> sorted.sortedByDescending { f -> f.videos.sumOf { effectiveDuration(ctx, it) } }
        }
        if (searching) return folders

        val byUri = HashMap<String, VideoItem>()
        for (f in all) for (v in f.videos) byUri.putIfAbsent(v.uri.toString(), v)

        val out = ArrayList<FolderItem>()

        val recent = PositionStore.recent(ctx, CONTINUE_LIMIT * 3)
            .mapNotNull { (uri, _) -> byUri[uri] }
            .take(CONTINUE_LIMIT)
        if (recent.isNotEmpty()) {
            out.add(
                FolderItem(CONTINUE_ID, ctx.getString(R.string.library_continue),
                    ctx.getString(R.string.library_continue_sub), 0L, recent)
            )
        }

        val favorites = FavoritesStore.all(ctx).mapNotNull { byUri[it] }
        if (favorites.isNotEmpty()) {
            out.add(
                FolderItem(FAVORITES_ID, ctx.getString(R.string.library_favorites),
                    ctx.getString(R.string.library_favorites_sub), 0L, sortVideos(ctx, favorites, sort))
            )
        }

        out.addAll(folders)
        return out
    }
}
