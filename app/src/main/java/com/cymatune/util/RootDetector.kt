package com.cymatune.util

import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Root Detection Utility
 * Checks if the device has root access available.
 * Play Store Compliant: Only detects, does not encourage rooting.
 */
object RootDetector {
    
    private const val TAG = "RootDetector"
    
    /**
     * Check if root access is available
     * Returns true if 'su' binary is accessible
     */
    fun isRootAvailable(): Boolean {
        return checkForSuBinary() || checkForRootPermission()
    }
    
    /**
     * Check if 'su' binary exists in common paths
     */
    private fun checkForSuBinary(): Boolean {
        val paths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/su/bin/su",
            "/magisk/.core/bin/su"
        )
        
        for (path in paths) {
            if (File(path).exists()) {
                Log.d(TAG, "Found su binary at: $path")
                return true
            }
        }
        
        return false
    }
    
    /**
     * Try to execute 'su' command to verify root access
     */
    private fun checkForRootPermission(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec("su")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            process.destroy()
            true
        } catch (e: Exception) {
            Log.d(TAG, "Root access check failed: ${e.message}")
            false
        }
    }
    
    /**
     * Execute a command with root privileges
     * Returns null if command fails or root not available
     */
    fun executeRootCommand(command: String): String? {
        if (!isRootAvailable()) {
            Log.w(TAG, "Root not available, cannot execute: $command")
            return null
        }
        
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                Log.d(TAG, "Root command executed successfully")
                output
            } else {
                Log.w(TAG, "Root command failed with exit code: $exitCode")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing root command", e)
            null
        }
    }
    
    /**
     * Check if Qualcomm DIAG interface is accessible
     * DIAG is used for reading baseband logs
     */
    fun isDiagInterfaceAvailable(): Boolean {
        if (!isRootAvailable()) return false
        
        val diagPaths = arrayOf(
            "/dev/diag",
            "/dev/diag_mdm",
            "/dev/ttydiag0"
        )
        
        for (path in diagPaths) {
            val result = executeRootCommand("test -e $path && echo 'exists'")
            if (result?.contains("exists") == true) {
                Log.d(TAG, "DIAG interface found at: $path")
                return true
            }
        }
        
        return false
    }
}
