package com.lightplayer.ui.tools

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.lightplayer.R
import com.lightplayer.theme.ThemeManager
import com.lightplayer.util.SubtitleFolders
import com.lightplayer.util.SubtitleMatcher

/**
 * Pick a folder with videos and subtitle files; Light Player pairs them up (by episode number
 * or name) and can rename the subtitle files to match their videos, so any player finds them.
 * The folder is also remembered so subtitles load automatically when a video starts.
 */
class SubtitleMatchActivity : AppCompatActivity() {

    private enum class State { RENAME, OK, BLOCKED, NO_MATCH }

    private class Plan(
        val sub: SubtitleFolders.Doc,
        val video: SubtitleFolders.Doc?,
        val newName: String?,
        val state: State
    )

    private var tree: Uri? = null
    private var plans: List<Plan> = emptyList()

    private lateinit var statusText: TextView
    private lateinit var savedText: TextView
    private lateinit var listBox: LinearLayout
    private lateinit var renameButton: Button

    private val folderPicker =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) onFolderChosen(uri)
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = ThemeManager.palette(this)
        ThemeManager.applyWindow(this, p)

        fun pillButton(label: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(if (filled) p.onAccent else p.accent)
            background = if (filled) ThemeManager.rounded(this@SubtitleMatchActivity, p.accent, 28f)
            else ThemeManager.rounded(this@SubtitleMatchActivity, 0x00000000, 28f, p.accent)
            setOnClickListener { onClick() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeManager.background(p)
            setPadding(dp(16), dp(12), dp(16), dp(16))
        }

        // header: back + title
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            ImageButton(this).apply {
                setImageResource(R.drawable.ic_back)
                imageTintList = ColorStateList.valueOf(p.text1)
                background = null
                contentDescription = getString(R.string.cd_back)
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(dp(44), dp(44))
        )
        header.addView(
            TextView(this).apply {
                setText(R.string.match_title)
                setTextColor(p.text1)
                textSize = 22f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) }
        )
        root.addView(header)

        root.addView(TextView(this).apply {
            setText(R.string.match_intro)
            setTextColor(p.text2)
            textSize = 14f
            setPadding(dp(4), dp(10), dp(4), dp(12))
        })

        root.addView(
            pillButton(getString(R.string.match_choose), true) { folderPicker.launch(null) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        )

        statusText = TextView(this).apply {
            setTextColor(p.text1)
            textSize = 15f
            setPadding(dp(4), dp(14), dp(4), dp(8))
        }
        root.addView(statusText)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(
            ScrollView(this).apply {
                background = ThemeManager.rounded(this@SubtitleMatchActivity, p.surface, 18f, p.stroke)
                addView(listBox)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        renameButton = pillButton("", true) { renameAll() }
        root.addView(
            renameButton,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(12) }
        )

        savedText = TextView(this).apply {
            setTextColor(p.text2)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(12), dp(4), dp(4))
        }
        root.addView(savedText)
        root.addView(
            pillButton(getString(R.string.match_forget), false) {
                SubtitleFolders.clear(this)
                Toast.makeText(this, R.string.match_forgot, Toast.LENGTH_SHORT).show()
                updateSaved()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46))
        )

        setContentView(root)
        statusText.setText(R.string.match_pick_hint)
        showRenameButton(0)
        updateSaved()
    }

    private fun updateSaved() {
        savedText.text = getString(R.string.match_saved_fmt, SubtitleFolders.saved(this).size)
    }

    private fun onFolderChosen(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Throwable) {
            // Renaming may not be possible in this folder, but reading can still work.
        }
        SubtitleFolders.add(this, uri)
        updateSaved()
        tree = uri
        analyze()
    }

    private fun analyze() {
        val folder = tree ?: return
        statusText.setText(R.string.match_scanning)
        Thread {
            val docs = SubtitleFolders.list(this, folder)
            val videos = docs.filter { SubtitleFolders.isVideo(it) }
            val subs = docs.filter { SubtitleFolders.isSubtitle(it.name) }
            val matches = SubtitleMatcher.matchAll(subs.map { it.name }, videos.map { it.name })
            val taken = docs.map { it.name.lowercase() }.toHashSet()

            val result = subs.mapIndexed { i, sub ->
                val video = if (matches[i] >= 0) videos[matches[i]] else null
                if (video == null) {
                    Plan(sub, null, null, State.NO_MATCH)
                } else {
                    val target = SubtitleMatcher.baseName(video.name) + "." + SubtitleMatcher.extension(sub.name)
                    when {
                        target == sub.name -> Plan(sub, video, target, State.OK)
                        taken.contains(target.lowercase()) -> Plan(sub, video, target, State.BLOCKED)
                        else -> Plan(sub, video, target, State.RENAME)
                    }
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                plans = result
                render(videos.size, subs.size)
            }
        }.start()
    }

    private fun render(videoCount: Int, subCount: Int) {
        val p = ThemeManager.palette(this)
        statusText.text = if (subCount == 0) getString(R.string.match_none_found)
        else getString(R.string.match_found_fmt, videoCount, subCount)

        listBox.removeAllViews()
        plans.forEach { plan ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            row.addView(TextView(this).apply {
                text = plan.sub.name
                setTextColor(p.text1)
                textSize = 14f
            })
            row.addView(TextView(this).apply {
                textSize = 12f
                when (plan.state) {
                    State.RENAME -> {
                        text = getString(R.string.match_to_fmt, plan.newName ?: "")
                        setTextColor(p.accent)
                    }
                    State.OK -> {
                        text = getString(R.string.match_ok)
                        setTextColor(p.text2)
                    }
                    State.BLOCKED -> {
                        text = getString(R.string.match_blocked_fmt, plan.newName ?: "")
                        setTextColor(p.text2)
                    }
                    State.NO_MATCH -> {
                        text = getString(R.string.match_no_video)
                        setTextColor(p.text2)
                    }
                }
            })
            listBox.addView(row)
        }
        showRenameButton(plans.count { it.state == State.RENAME })
    }

    private fun showRenameButton(count: Int) {
        renameButton.text =
            if (count > 0) getString(R.string.match_rename_fmt, count) else getString(R.string.match_rename_none)
        renameButton.isEnabled = count > 0
        renameButton.alpha = if (count > 0) 1f else 0.4f
    }

    private fun renameAll() {
        val todo = plans.filter { it.state == State.RENAME && it.newName != null }
        if (todo.isEmpty()) return
        renameButton.isEnabled = false
        Thread {
            var done = 0
            for (plan in todo) {
                try {
                    if (DocumentsContract.renameDocument(contentResolver, plan.sub.uri, plan.newName!!) != null) done++
                } catch (_: Throwable) {
                    // Skip files that cannot be renamed.
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                Toast.makeText(this, getString(R.string.match_done_fmt, done), Toast.LENGTH_LONG).show()
                analyze()
            }
        }.start()
    }
}
