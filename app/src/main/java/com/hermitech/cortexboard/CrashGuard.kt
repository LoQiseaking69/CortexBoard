package com.hermitech.cortexboard

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Persists uncaught exceptions so the next launch can show them. The viewer uses plain
 * Android views (no Compose) so it still works if the crash is in the UI layer itself.
 */
object CrashGuard {
    private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                file(app).writeText(
                    "CortexBoard ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                        "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                        "thread: ${thread.name}\n\n$sw"
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Records a caught (non-fatal) failure so it is visible on the next launch. */
    fun record(ctx: Context, where: String, error: Throwable) {
        runCatching {
            val sw = StringWriter(); error.printStackTrace(PrintWriter(sw))
            file(ctx.applicationContext).appendText(
                "NON-FATAL in $where · ${BuildConfig.VERSION_NAME} · API ${Build.VERSION.SDK_INT} · ${Build.MODEL}\n$sw\n"
            )
        }
    }

    fun pending(ctx: Context): String? = file(ctx).takeIf { it.exists() }?.readText()

    fun clear(ctx: Context) { file(ctx).delete() }

    /** Shows the saved trace. [onContinue] is called when the user dismisses it. */
    fun show(activity: Activity, trace: String, onContinue: () -> Unit) {
        val pad = (16 * activity.resources.displayMetrics.density).toInt()
        val text = TextView(activity).apply {
            setText(trace)
            setTextColor(Color.parseColor("#CDF8FF"))
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        val title = TextView(activity).apply {
            setText("// CORTEX CRASH REPORT")
            setTextColor(Color.parseColor("#FF2BD6"))
            typeface = Typeface.MONOSPACE
            textSize = 16f
        }
        val copy = Button(activity).apply {
            setText("COPY TRACE")
            setOnClickListener {
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("crash", trace))
                Toast.makeText(activity, "Copied", Toast.LENGTH_SHORT).show()
            }
        }
        val cont = Button(activity).apply {
            setText("CLEAR & CONTINUE")
            setOnClickListener { clear(activity); onContinue() }
        }
        val col = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
            setBackgroundColor(Color.parseColor("#040609"))
            addView(title)
            addView(LinearLayout(activity).apply { addView(copy); addView(cont) })
            addView(ScrollView(activity).apply { addView(text) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        activity.setContentView(col)
    }
}
