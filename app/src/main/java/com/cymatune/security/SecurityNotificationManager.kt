package com.cymatune.security

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.cymatune.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 4.5: Intrusion-Free Notification System.
 * Centralizes all notifications to enforce the STRICT "Silent and Consolidated" policies.
 */
object SecurityNotificationManager {

    private const val CHANNEL_ID = "cymatune_silent_threats"
    private const val PREFS_NAME = "cymatune_settings"
    
    const val KEY_NOTIFICATIONS_ENABLED = "notifications_enabled"
    const val KEY_DAILY_LIMIT = "notifications_daily_limit"
    
    private const val KEY_ALERT_COUNT = "notifications_alert_count_"
    private const val KEY_LAST_DATE = "notifications_last_date"

    // In-memory tracker for consolidated notification counts during a session
    private val sessionCounts = mutableMapOf<String, Int>()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun getCurrentDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    private fun checkAndResetDailyCount(prefs: SharedPreferences) {
        val today = getCurrentDateString()
        val lastDate = prefs.getString(KEY_LAST_DATE, "")
        
        if (lastDate != today) {
            prefs.edit()
                .putString(KEY_LAST_DATE, today)
                .putInt(KEY_ALERT_COUNT, 0)
                .apply()
        }
    }

    fun isEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_NOTIFICATIONS_ENABLED, false)
    }

    private fun canShowNotificationToday(context: Context): Boolean {
        val prefs = getPrefs(context)
        checkAndResetDailyCount(prefs)

        val isEnabled = prefs.getBoolean(KEY_NOTIFICATIONS_ENABLED, false)
        if (!isEnabled) return false

        val dailyLimit = prefs.getInt(KEY_DAILY_LIMIT, 0)
        if (dailyLimit <= 0) return false // Floor is 0

        val currentCount = prefs.getInt(KEY_ALERT_COUNT, 0)
        return currentCount < dailyLimit
    }
    
    private fun incrementDailyCount(context: Context) {
        val prefs = getPrefs(context)
        val currentCount = prefs.getInt(KEY_ALERT_COUNT, 0)
        prefs.edit().putInt(KEY_ALERT_COUNT, currentCount + 1).apply()
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Security Threat Alerts (Silent)"
            val descriptionText = "Consolidated alerts for network threats"
            
            // STRICT SILENCE: Low importance prevents sound, vibration, and peeking.
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(false)
                vibrationPattern = null
                setSound(null, null)
                enableLights(false)
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Shows a consolidated notification. If the same type of threat is detected, it will update
     * the existing notification with a higher count rather than spawning a new one.
     */
    fun notify(context: Context, threatType: String, message: String) {
        if (!canShowNotificationToday(context)) return

        createNotificationChannel(context)

        // Increment session count for consolidation
        val count = sessionCounts.getOrDefault(threatType, 0) + 1
        sessionCounts[threatType] = count

        val title = if (count > 1) "$threatType ($count) detected" else "$threatType detected"
        
        // Generate a stable ID based on the threat type string hash (positive)
        val notificationId = threatType.hashCode() and 0x7FFFFFFF

        val intent = Intent(context, Class.forName("com.cymatune.MainActivity")).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification) // Ensure this icon exists!
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_LOW) // Silent priority
            .setContentIntent(pendingIntent)
            .setGroup("cymatune_threats") // Bundle them together system-wide
            .setAutoCancel(true)
            .setOnlyAlertOnce(true) // Never buzz on updates
            .setDefaults(0) // Remove default sound/vibrate

        try {
            NotificationManagerCompat.from(context).notify(notificationId, builder.build())
            
            // Only increment limit for a brand-new notification ID (not an update)
            if (count == 1) {
                incrementDailyCount(context)
            }
        } catch (e: SecurityException) {
            // Permission missing (Android 13+ POST_NOTIFICATIONS)
        }
    }
}
