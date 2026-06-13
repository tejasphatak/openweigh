package io.github.openweigh.diag

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Captures the stack trace of the last uncaught exception to a file in the app's private storage,
 * so the bug-report screen can surface "the app crashed last time — here's why" across restarts.
 *
 * Installed once from [io.github.openweigh.BleScaleApp.onCreate]. It chains to any previously
 * registered handler, so the normal crash/restart behaviour is preserved. Nothing leaves the
 * device; the user decides whether to include the crash in a shared report.
 */
object CrashReporter {

    private const val FILE_NAME = "last_crash.txt"

    @Volatile
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrash(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrash(context: Context, thread: Thread, throwable: Throwable) {
        val trace = StringWriter().also { sw -> PrintWriter(sw).use { throwable.printStackTrace(it) } }
        val text = buildString {
            append("Thread: ").append(thread.name).append('\n')
            append(trace.toString())
        }
        file(context).writeText(text)
    }

    /** The last persisted crash trace, or null if there hasn't been one (or it was cleared). */
    fun readLastCrash(context: Context): String? {
        val f = file(context.applicationContext)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun clear(context: Context) {
        runCatching { file(context.applicationContext).delete() }
    }

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)
}
