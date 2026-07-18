package com.vynex.gumshoos

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * A small ring of timestamped events, persisted so it survives the process being killed.
 *
 * Exists to answer one question that reading the code cannot: when the app reopens unexpectedly,
 * did *we* call startActivity again, or did the system bring the task forward by itself? Those have
 * opposite fixes, and guessing between them has already cost two wrong attempts.
 */
object EventLog {
    private const val FILE_NAME = "events.log"
    private const val MAX_LINES = 120

    private val buffer = ArrayDeque<String>()

    /** Single thread so writes stay ordered without holding the lock across I/O. */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "gumshoos-eventlog").apply { isDaemon = true }
    }
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        runCatching {
            file(context)?.takeIf { it.exists() }?.readLines()?.forEach { buffer.addLast(it) }
        }
        while (buffer.size > MAX_LINES) buffer.removeFirst()
    }

    /**
     * Uptime rather than wall clock: the point is intervals between events, not time of day.
     *
     * The in-memory append is synchronous but the file write is not. record() is called from the
     * show path, and that path is the whole point of the architecture — flushing ~120 lines to
     * internal storage inline would spend a real fraction of a sub-20ms budget on instrumentation.
     */
    fun record(event: String) {
        val line = "%8.3fs  %s".format(SystemClock.uptimeMillis() / 1000.0, event)
        Log.i(OverlayService.TAG, "event: $event")
        val snapshot: String
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
            snapshot = buffer.joinToString("\n")
        }
        writer.execute { runCatching { file(appContext)?.writeText(snapshot) } }
    }

    fun read(): String = synchronized(buffer) {
        if (buffer.isEmpty()) "" else buffer.joinToString("\n")
    }

    fun clear() = synchronized(buffer) {
        buffer.clear()
        runCatching { file(appContext)?.delete() }
        Unit
    }

    private fun file(context: Context?) = context?.let { File(it.filesDir, FILE_NAME) }
}
