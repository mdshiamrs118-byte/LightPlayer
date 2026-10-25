package com.lightplayer

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Catches crashes, saves a readable report and shows [CrashActivity] instead of the
 * silent "app keeps stopping" behaviour.
 */
object CrashHandler {

    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_REPORT_CHARS = 60_000

    fun install(app: Application) {
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                File(app.filesDir, FILE_NAME).writeText(buildReport(app, thread, error))
                app.startActivity(
                    Intent(app, CrashActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            } catch (_: Throwable) {
                // Never let the crash reporter itself crash.
            }
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }

    fun lastReport(ctx: Context): String? {
        val f = File(ctx.filesDir, FILE_NAME)
        return if (f.exists()) try { f.readText() } catch (_: Throwable) { null } else null
    }

    fun clear(ctx: Context) {
        try { File(ctx.filesDir, FILE_NAME).delete() } catch (_: Throwable) {}
    }

    private fun buildReport(ctx: Context, thread: Thread, error: Throwable): String {
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (_: Throwable) {
            "?"
        }
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            appendLine("Light Player crash report")
            appendLine("Time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            appendLine("App version: $version")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Thread: ${thread.name}")
            appendLine()
            append(trace)
        }
        return if (text.length > MAX_REPORT_CHARS) text.take(MAX_REPORT_CHARS) else text
    }
}
