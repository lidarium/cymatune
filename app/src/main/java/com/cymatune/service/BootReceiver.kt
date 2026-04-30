package com.cymatune.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Receiver to start the FakeTowerDetectionService when the device boots.
 * This ensures continuous monitoring capability.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
        Log.d("BootReceiver", "Boot completed received, starting FakeTowerDetectionService")

        // SECURITY CHECK: Block Rooted Devices (Defense-in-Depth)
            if (com.cymatune.security.RootDetectionUtil.isDeviceRooted()) {
                Log.e("BootReceiver", "Root detected. Blocking service autostart.")
                return
            }
            
            // Check if monitoring is enabled in preferences before starting
            val prefs = context.getSharedPreferences("cymatune_prefs", Context.MODE_PRIVATE)
            val isMonitoringEnabled = prefs.getBoolean("monitoring_enabled", true) // Default to true
            
            if (isMonitoringEnabled) {
                startDetectionService(context)
            } else {
                Log.d("BootReceiver", "Monitoring is disabled in settings, not starting service")
            }
        }
    }

    private fun startDetectionService(context: Context) {
        val serviceIntent = Intent(context, FakeTowerDetectionService::class.java)
        
        try {
            // For Android 8.0+ (Oreo) and above, we must use startForegroundService
            // The service MUST call startForeground() within 5 seconds
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, serviceIntent)
                Log.d("BootReceiver", "Started service using startForegroundService")
            } else {
                context.startService(serviceIntent)
                Log.d("BootReceiver", "Started service using startService")
            }
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to start service on boot", e)
        }
    }
}
