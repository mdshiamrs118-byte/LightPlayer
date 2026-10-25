package com.lightplayer.util

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Folders the user picked for subtitle files. Android only lets an app read files that are not
 * media (like .srt) from folders the user explicitly chose, so we remember those choices.
 */
object SubtitleFolders {

    class Doc(val id: String, val name: String, val mime: String, val uri: Uri)

    private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "webm", "ts", "m4v", "flv", "wmv", "3gp", "mpg", "mpeg")

    fun isSubtitle(name: String) = SubtitleMatcher.extension(name).lowercase() in SUBTITLE_EXTENSIONS

    fun isVideo(doc: Doc) =
        doc.mime.startsWith("video/") || SubtitleMatcher.extension(doc.name).lowercase() in VIDEO_EXTENSIONS

    fun saved(ctx: Context): List<Uri> = Prefs.subtitleFolders(ctx).map { Uri.parse(it) }

    fun add(ctx: Context, tree: Uri) {
        Prefs.setSubtitleFolders(ctx, Prefs.subtitleFolders(ctx) + tree.toString())
    }

    fun clear(ctx: Context) {
        Prefs.setSubtitleFolders(ctx, emptySet())
    }

    /** Files directly inside the folder (no sub-folders). */
    fun list(ctx: Context, tree: Uri): List<Doc> {
        val out = ArrayList<Doc>()
        try {
            val parent = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            ctx.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                    out.add(Doc(id, name, mime, DocumentsContract.buildDocumentUriUsingTree(tree, id)))
                }
            }
        } catch (_: Throwable) {
            // Folder removed or access revoked: treat as empty.
        }
        return out
    }

    /** Best subtitle for [videoName] across all saved folders: its file and display name. */
    fun findFor(ctx: Context, videoName: String): Pair<Uri, String>? {
        for (tree in saved(ctx)) {
            val subs = list(ctx, tree).filter { isSubtitle(it.name) }
            if (subs.isEmpty()) continue
            val index = SubtitleMatcher.bestSubtitle(videoName, subs.map { it.name })
            if (index >= 0) return subs[index].uri to subs[index].name
        }
        return null
    }
}
