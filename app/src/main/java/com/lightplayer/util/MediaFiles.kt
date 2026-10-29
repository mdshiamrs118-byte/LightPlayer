package com.lightplayer.util

import android.content.Context
import android.net.Uri

object MediaFiles {

    /**
     * True when the file behind a media-library entry is really there. Android's media library
     * can keep entries for files that were deleted with a file manager.
     */
    fun exists(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: java.io.FileNotFoundException) {
        false  // the media library lists it, but the file is really gone
    } catch (_: Throwable) {
        true   // cannot tell (permission, busy provider...): never throw a record away on a guess
    }
}
