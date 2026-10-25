package com.lightplayer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Friendly crash screen. It runs in its own process (":crash") so it can appear even though the
 * app's main process has just died. Also used from Settings to view the last crash report.
 */
class CrashActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIEW_ONLY = "view_only"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewOnly = intent.getBooleanExtra(EXTRA_VIEW_ONLY, false)
        val report = CrashHandler.lastReport(this) ?: "No crash report was saved."

        val bg = 0xFF0F1115.toInt()
        val accent = 0xFF8CB4FF.toInt()
        window.statusBarColor = bg
        window.navigationBarColor = bg

        fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

        fun button(label: String, filled: Boolean, onClick: () -> Unit) = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(if (filled) 0xFF0F1115.toInt() else accent)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                if (filled) setColor(accent) else {
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1), accent)
                }
            }
            setOnClickListener { onClick() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(36), dp(20), dp(20))
        }
        root.addView(TextView(this).apply {
            text = if (viewOnly) getString(R.string.crash_last_title) else getString(R.string.crash_title)
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.crash_message)
            setTextColor(0xFFB8BCC4.toInt())
            textSize = 14f
            setPadding(0, dp(8), 0, dp(12))
        })

        val logView = TextView(this).apply {
            text = report
            setTextColor(0xFFD0D4DC.toInt())
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xFF171A21.toInt())
            }
        }
        root.addView(
            ScrollView(this).apply { addView(logView) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        }
        fun add(b: Button) = row.addView(
            b,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
        )
        add(button(getString(R.string.crash_copy), false) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Light Player crash report", report))
            Toast.makeText(this, R.string.crash_copied, Toast.LENGTH_SHORT).show()
        })
        add(button(getString(R.string.crash_share), false) {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Light Player crash report")
                putExtra(Intent.EXTRA_TEXT, report)
            }
            startActivity(Intent.createChooser(send, getString(R.string.crash_share)))
        })
        root.addView(row)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        if (viewOnly) {
            row2.addView(
                button(getString(R.string.crash_clear), false) {
                    CrashHandler.clear(this)
                    finish()
                },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(4); marginEnd = dp(4)
                }
            )
            row2.addView(
                button(getString(R.string.action_done), true) { finish() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(4); marginEnd = dp(4)
                }
            )
        } else {
            row2.addView(
                button(getString(R.string.crash_restart), true) {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    )
                    finishAndRemoveTask()
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginStart = dp(4); marginEnd = dp(4) }
            )
        }
        root.addView(row2)
        setContentView(root)
    }
}
