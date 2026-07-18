package com.vynex.gumshoos

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures uncaught exceptions to a file so a crash can be diagnosed from the device itself.
 *
 * The overlay lives in a service, so its crashes never reach the UI — and without adb there's
 * nowhere else for the stack trace to go.
 */
object CrashLog {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { write(appContext, thread, throwable) }
            // Chain to the platform handler so the process still dies normally.
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        file(context).writeText(
            """
            $timestamp
            thread: ${thread.name}
            process: ${android.os.Process.myPid()}

            $stack
            """.trimIndent()
        )
    }

    fun read(context: Context): String? =
        file(context).takeIf { it.exists() }?.readText()?.ifBlank { null }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
