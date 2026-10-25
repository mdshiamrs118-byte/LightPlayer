package com.lightplayer.ui.music

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.lightplayer.R
import com.lightplayer.tts.TtsIndex
import com.lightplayer.util.MediaFiles

/**
 * Deletes files from Android's media library and tells the user what happened.
 *
 * Android only lets an app delete media files it owns without asking. For anything else
 * (for example files created before a reinstall) it shows its own "allow deleting?" screen,
 * which this class opens instead of failing silently.
 *
 * Create it as a property of the fragment, so it can register for the system result.
 */
class MediaDeleter(
    private val fragment: Fragment,
    private val onFinished: () -> Unit
) {

    private val main = Handler(Looper.getMainLooper())
    private var requested: List<Uri> = emptyList()
    private var queue = ArrayDeque<Uri>()      // Android 10: one confirmation per file
    private var current: Uri? = null

    private val launcher = fragment.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> onSystemResult(result.resultCode == Activity.RESULT_OK) }

    fun delete(uris: List<Uri>) {
        if (uris.isEmpty()) {
            onFinished()
            return
        }
        requested = uris
        val ctx = fragment.requireContext().applicationContext
        Thread {
            val denied = ArrayList<Uri>()
            for (uri in uris) {
                try {
                    ctx.contentResolver.delete(uri, null, null)
                } catch (_: SecurityException) {
                    denied.add(uri)
                } catch (_: Throwable) {
                    // Not found / already gone: nothing to do.
                }
                // Some devices ignore the request without an error: if the file is still there,
                // let Android ask the user instead.
                if (uri !in denied && MediaFiles.exists(ctx, uri)) denied.add(uri)
            }
            main.post {
                if (!fragment.isAdded) return@post
                if (denied.isEmpty()) finish() else askAndroid(denied)
            }
        }.start()
    }

    // ---------- asking Android for permission ----------

    private fun askAndroid(denied: List<Uri>) {
        val ctx = fragment.requireContext()
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val request = MediaStore.createDeleteRequest(ctx.contentResolver, denied)
                launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            } catch (_: Throwable) {
                finish()
            }
        } else {
            queue = ArrayDeque(denied)
            nextOnAndroid10()
        }
    }

    private fun nextOnAndroid10() {
        val uri = queue.removeFirstOrNull()
        if (uri == null) {
            finish()
            return
        }
        current = uri
        val resolver = fragment.requireContext().contentResolver
        try {
            resolver.delete(uri, null, null)
            nextOnAndroid10()
        } catch (e: RecoverableSecurityException) {
            launcher.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
        } catch (_: Throwable) {
            nextOnAndroid10()
        }
    }

    private fun onSystemResult(approved: Boolean) {
        if (!fragment.isAdded) return
        if (Build.VERSION.SDK_INT >= 30) {
            finish()   // Android 11+ has already deleted the files if the user agreed
        } else {
            if (approved) {
                try {
                    current?.let { fragment.requireContext().contentResolver.delete(it, null, null) }
                } catch (_: Throwable) {
                }
            }
            nextOnAndroid10()
        }
    }

    // ---------- wrapping up ----------

    private fun finish() {
        val ctx = fragment.requireContext().applicationContext
        Thread {
            val gone = requested.count { !MediaFiles.exists(ctx, it) }
            pruneIndex(ctx)
            main.post {
                if (!fragment.isAdded) return@post
                val ok = gone == requested.size
                Toast.makeText(
                    ctx,
                    if (ok) ctx.getString(R.string.music_deleted_fmt, gone) else ctx.getString(R.string.music_delete_failed),
                    Toast.LENGTH_SHORT
                ).show()
                onFinished()
            }
        }.start()
    }

    companion object {
        /** Forget generated-audio records whose files no longer exist. */
        fun pruneIndex(ctx: Context) {
            val index = TtsIndex(ctx)
            for (entry in index.all()) {
                if (!MediaFiles.exists(ctx, Uri.parse(entry.uri))) index.remove(entry.uri)
            }
        }
    }
}
