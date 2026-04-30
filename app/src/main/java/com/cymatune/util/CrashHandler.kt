package com.cymatune.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Captures uncaught exceptions and saves them to a local file in internal storage.
 * Essential for "off-store" debugging where Play Console crash reports are unavailable.
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {

    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        saveCrashReport(throwable)
        // Delegate to default handler to let the app crash naturally (or kill process)
        defaultHandler?.uncaughtException(thread, throwable) ?: exitProcess(1)
    }

    private fun saveCrashReport(throwable: Throwable) {
        try {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            val stackTrace = sw.toString()

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val filename = "crash_$timestamp.txt"
            
            // Save to internal storage (getFilesDir/crash_logs)
            // Path: /data/user/0/com.cymatune/files/crash_logs/
            val dir = File(context.filesDir, "crash_logs")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, filename)

            FileWriter(file).use { writer ->
                writer.write("Crash Report: $timestamp\n")
                writer.write("Package: ${context.packageName}\n")
                writer.write("Version: ${com.cymatune.BuildConfig.VERSION_NAME} (${com.cymatune.BuildConfig.VERSION_CODE})\n\n")
                writer.write(stackTrace)
            }
            Log.e("CrashHandler", "Crash report saved to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("CrashHandler", "Failed to save crash report", e)
        }
    }

    companion object {
        fun init(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(context))
        }
    }
}
