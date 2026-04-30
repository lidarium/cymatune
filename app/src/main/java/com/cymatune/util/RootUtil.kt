package com.cymatune.util

import android.content.Context
import android.util.Log
import java.io.DataOutputStream
import java.io.IOException

object RootUtil {
    private const val TAG = "RootUtil"

    /**
     * Check if the device is rooted
     */
    fun isDeviceRooted(): Boolean {
        return checkRootMethod1() || checkRootMethod2() || checkRootMethod3()
    }

    private fun checkRootMethod1(): Boolean {
        val buildTags = android.os.Build.TAGS
        return buildTags != null && buildTags.contains("test-keys")
    }

    private fun checkRootMethod2(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/system/sd/xbin/su",
            "/system/bin/failsafe/su",
            "/data/local/su"
        )
        for (path in paths) {
            if (java.io.File(path).exists()) return true
        }
        return false
    }

    private fun checkRootMethod3(): Boolean {
        var process: Process? = null
        return try {
            process = Runtime.getRuntime().exec(arrayOf("/system/xbin/which", "su"))
            val inStream = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
            inStream.readLine() != null
        } catch (t: Throwable) {
            false
        } finally {
            process?.destroy()
        }
    }

    /**
     * Attempt to toggle Airplane Mode via Root
     * Returns true if successful, false otherwise
     */
    fun setAirplaneMode(enable: Boolean): Boolean {
        if (!isDeviceRooted()) {
            Log.e(TAG, "Cannot toggle Airplane Mode: Device not rooted")
            return false
        }

        return try {
            val p = Runtime.getRuntime().exec("su")
            val os = DataOutputStream(p.outputStream)
            
            // Enable/Disable Airplane Mode Setting
            val value = if (enable) "1" else "0"
            os.writeBytes("settings put global airplane_mode_on $value\n")
            
            // Broadcast the intent (Required for Android to see the change)
            os.writeBytes("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enable\n")
            
            os.writeBytes("exit\n")
            os.flush()
            p.waitFor()
            
            Log.i(TAG, "Airplane mode toggled to $enable via Root")
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to toggle Airplane Mode via Root", e)
            false
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while toggling Airplane Mode", e)
            false
        }
    }
}
