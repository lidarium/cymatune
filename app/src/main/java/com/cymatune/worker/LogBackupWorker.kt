package com.cymatune.worker

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.work.ListenableWorker

class LogBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val filename = "cymatune_log_dump_$timestamp.txt"
            
            // Collect logs command
            // Note: On some devices logcat -d might need permissions or behave differently.
            // But this is the standard way to dump own logs or accessible logs.
            val process = Runtime.getRuntime().exec("logcat -d -v threadtime")
            val reader = process.inputStream.bufferedReader()
            val logs = reader.use { it.readText() }
            
            // Save to Downloads/CymatuneLogs
            // Note: Scoped Storage might restrict this on Android 11+ without MediaStore.
            // Fallback to app specific external storage if Downloads fails or is restricted.
            val targetDir = try {
                 val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                 File(downloads, "CymatuneLogs").also { if (!it.exists()) it.mkdirs() }
            } catch (e: Exception) {
                 applicationContext.getExternalFilesDir(null) ?: applicationContext.filesDir
            }
            
            val logFile = File(targetDir, filename)
            FileWriter(logFile).use { it.write(logs) }
            
            android.util.Log.d("LogBackupWorker", "Logs backed up to: ${logFile.absolutePath}")
            ListenableWorker.Result.success()
        } catch (e: Exception) {
            android.util.Log.e("LogBackupWorker", "Failed to backup logs", e)
            ListenableWorker.Result.failure()
        }
    }
}
