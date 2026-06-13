package io.github.openweigh.diag

import android.util.Log
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Lightweight in-memory log buffer and facade over [android.util.Log].
 *
 * Anything logged through [d]/[i]/[w]/[e] is both forwarded to Logcat and retained in a bounded
 * ring buffer that the bug-report / diagnostics feature reads back — so a shared report carries
 * recent app activity without needing the `READ_LOGS` permission or any backend. The buffer lives
 * only in memory and never leaves the device unless the user explicitly shares a report.
 */
object DiagLog {

    private const val CAPACITY = 300
    private val buffer = ArrayDeque<String>(CAPACITY)
    private val timestamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    private fun record(level: Char, tag: String, message: String, t: Throwable?) {
        val line = buildString {
            append(timestamp.format(Date())).append(' ').append(level).append('/').append(tag)
            append(": ").append(message)
            if (t != null) append(" | ").append(t.javaClass.simpleName).append(": ").append(t.message)
        }
        if (buffer.size >= CAPACITY) buffer.pollFirst()
        buffer.addLast(line)
    }

    fun d(tag: String, message: String, t: Throwable? = null) { Log.d(tag, message, t); record('D', tag, message, t) }
    fun i(tag: String, message: String, t: Throwable? = null) { Log.i(tag, message, t); record('I', tag, message, t) }
    fun w(tag: String, message: String, t: Throwable? = null) { Log.w(tag, message, t); record('W', tag, message, t) }
    fun e(tag: String, message: String, t: Throwable? = null) { Log.e(tag, message, t); record('E', tag, message, t) }

    /** Snapshot of the retained log lines, oldest first. */
    @Synchronized
    fun snapshot(): List<String> = buffer.toList()

    @Synchronized
    fun clear() = buffer.clear()
}
