package org.photoall.tether

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every failure the app meets, kept on the phone (files/errors.log) with its stack trace and
 * what led to it, so a report can be copied from the app and sent back. Crashes are written
 * by an uncaught-exception handler before the process ends, and shown on the next start.
 */
object ErrorLog {
    private lateinit var file: File
    private lateinit var crashFlag: File
    private var header = ""
    private const val LIMIT = 400 * 1024
    /** Extra context added to every entry (the connection log's tail). */
    @Volatile var context: () -> String = { "" }

    fun init(app: Context) {
        file = File(app.filesDir, "errors.log")
        crashFlag = File(app.filesDir, "crashed")
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull()
        header = "Photo All 联机 $version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                record("崩溃（线程 ${thread.name}）", e)
                crashFlag.writeText(System.currentTimeMillis().toString())
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Records a failure: where it happened, the exception (with its causes), and [detail]. */
    fun record(where: String, e: Throwable? = null, detail: String = "") {
        if (!::file.isInitialized) return
        val text = buildString {
            append("==== ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
            append("  ").append(where).append('\n')
            append(header).append('\n')
            if (e != null) append(trace(e))
            if (detail.isNotBlank()) append("-- 详情\n").append(detail.trimEnd()).append('\n')
            val ctx = runCatching { context() }.getOrDefault("")
            if (ctx.isNotBlank()) append("-- 之前的记录\n").append(ctx.trimEnd()).append('\n')
            append('\n')
        }
        android.util.Log.e("ErrorLog", text)
        synchronized(this) {
            runCatching {
                if (file.length() > LIMIT) {
                    // keep the newer half
                    val kept = file.readText().takeLast(LIMIT / 2)
                    file.writeText("（更早的记录已截掉）\n" + kept.substringAfter("==== ", kept).let { "==== $it" })
                }
                file.appendText(text)
            }
        }
    }

    /** The stack trace with causes, the app's own frames in full and others counted. */
    fun trace(e: Throwable): String {
        val sw = StringWriter()
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 5) {
            sw.append(if (depth == 0) "" else "原因：").append(t.javaClass.name).append(": ").append(t.message ?: "").append('\n')
            var skipped = 0
            for (frame in t.stackTrace.take(40)) {
                if (frame.className.startsWith("org.photoall")) {
                    if (skipped > 0) sw.append("    …（$skipped 个系统调用）\n")
                    skipped = 0
                    sw.append("    at ").append(frame.toString()).append('\n')
                } else skipped++
            }
            if (skipped > 0) sw.append("    …（$skipped 个系统调用）\n")
            t = t.cause.takeIf { it !== t }
            depth++
        }
        return sw.toString()
    }

    fun full(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()

    fun text(): String = synchronized(this) { runCatching { file.readText() }.getOrDefault("") }

    fun clear() = synchronized(this) { runCatching { file.delete(); crashFlag.delete() } }

    /** True once after a crash (the next start shows the report). */
    fun takeCrashFlag(): Boolean = runCatching { crashFlag.exists().also { if (it) crashFlag.delete() } }.getOrDefault(false)

    val count: Int get() = Regex("^==== ", RegexOption.MULTILINE).findAll(text()).count()
}
