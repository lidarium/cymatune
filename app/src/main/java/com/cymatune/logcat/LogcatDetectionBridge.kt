package com.cymatune.logcat

import android.content.Context
import android.util.Log
import com.cymatune.security.SecurityNotificationManager
import com.cymatune.service.LogcatService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Bridge between LogcatService threat detection and the main FakeTowerDetector.
 * Provides trust score modifiers and threat information to enhance detection accuracy.
 */
object LogcatDetectionBridge {
    
    private const val TAG = "LogcatDetectionBridge"
    private const val NOTIFICATION_CHANNEL_ID = "cymatune_logcat_threats"
    private const val NOTIFICATION_ID_SILENT_SMS = 2001
    private const val NOTIFICATION_ID_ENCRYPTION = 2002
    private const val NOTIFICATION_ID_IMSI = 2003
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isInitialized = false
    private var appContext: Context? = null
    
    // Current tower tracking
    private var currentTowerCid: Int? = null
    private var currentTowerLac: Int? = null
    private var currentTowerMcc: Int? = null
    private var currentTowerMnc: Int? = null
    
// Public alert flow for UI observation (replays last 10 alerts)
private val _alertFlow = MutableSharedFlow<ThreatAlert>(replay = 10)
val alertFlow = _alertFlow.asSharedFlow()

// Cached threat data for quick access
    private var cachedThreatScore: LogcatThreatScore? = null
    private var recentAlerts = mutableListOf<ThreatAlert>()
    private const val MAX_CACHED_ALERTS = 50
    
    // Silent SMS tracking with tower info
    data class SilentSmsRecord(
        val timestamp: Long,
        val smsType: String,
        val cid: Int?,
        val lac: Int?,
        val mcc: Int?,
        val mnc: Int?
    )
    private val silentSmsHistory = mutableListOf<SilentSmsRecord>()
    
    // Weights for integrating logcat score into trust score
    private const val LOGCAT_WEIGHT_WHEN_ACTIVE = 0.25f
    private const val ENCRYPTION_IMPACT = 0.4f
    private const val SILENT_SMS_IMPACT = 0.3f
    private const val IMSI_CATCHER_IMPACT = 0.5f
    
    /**
     * Initialize the bridge - call from FakeTowerDetectionService
     */
    fun initialize(context: Context) {
        if (isInitialized) return
        isInitialized = true
        appContext = context.applicationContext
        
        // Channel creation is handled by SecurityNotificationManager
        
        // Subscribe to threat updates
        scope.launch {
            LogcatService.threatFlow.collectLatest { score ->
                cachedThreatScore = score
                if (score.hasThreat) {
                    Log.w(TAG, "Threat score updated: ${score.combinedScore} - ${score.reasons}")
                }
            }
        }
        
        // Subscribe to alerts and show notifications
        scope.launch {
            LogcatService.alertFlow.collectLatest { alert ->
                // Inject current tower if not set
                val enrichedAlert = if (alert.cid == null && currentTowerCid != null) {
                    alert.copy(cid = currentTowerCid)
                } else {
                    alert
                }
                
                synchronized(recentAlerts) {
                    recentAlerts.add(0, enrichedAlert)
                    while (recentAlerts.size > MAX_CACHED_ALERTS) {
                        recentAlerts.removeAt(recentAlerts.lastIndex)
                    }
                }
                
                // Track silent SMS with tower info
                if (enrichedAlert.type == ThreatType.SILENT_SMS) {
                    synchronized(silentSmsHistory) {
                        silentSmsHistory.add(0, SilentSmsRecord(
                            timestamp = enrichedAlert.timestamp,
                            smsType = enrichedAlert.message,
                            cid = currentTowerCid,
                            lac = currentTowerLac,
                            mcc = currentTowerMcc,
                            mnc = currentTowerMnc
                        ))
                        // Keep last 100 records
                        while (silentSmsHistory.size > 100) {
                            silentSmsHistory.removeAt(silentSmsHistory.lastIndex)
                        }
                    }
                }
                
Log.w(TAG, "Alert received: ${enrichedAlert.type} - ${enrichedAlert.message} (Tower: CID=${enrichedAlert.cid})")

            // Emit to public flow for UI observation
            _alertFlow.emit(enrichedAlert)

            // Show Android notification for critical/high threats
                if (enrichedAlert.severity in setOf(ThreatSeverity.HIGH, ThreatSeverity.CRITICAL)) {
                    showThreatNotification(enrichedAlert)
                }
            }
        }
        
        Log.i(TAG, "LogcatDetectionBridge initialized with unified alert persistence")
    }
    
    /**
     * Update current tower info - call from FakeTowerDetectionService during scans
     */
    fun setCurrentTower(cid: Int?, lac: Int?, mcc: Int?, mnc: Int?) {
currentTowerCid = cid
    currentTowerLac = lac
    currentTowerMcc = mcc
    currentTowerMnc = mnc
}
    
    /**
     * Get current tower info
     */
    fun getCurrentTowerCid(): Int? = currentTowerCid
    fun getCurrentTowerLac(): Int? = currentTowerLac
    fun getCurrentTowerMcc(): Int? = currentTowerMcc
    fun getCurrentTowerMnc(): Int? = currentTowerMnc
    
    /**
     * Get silent SMS history
     */
    fun getSilentSmsHistory(): List<SilentSmsRecord> {
        return synchronized(silentSmsHistory) {
            silentSmsHistory.toList()
        }
    }
    
    /**
     * Get silent SMS count for a specific tower
     */
    fun getSilentSmsCountForTower(cid: Int): Int {
        return synchronized(silentSmsHistory) {
            silentSmsHistory.count { it.cid == cid }
        }
    }
    
    private fun showThreatNotification(alert: ThreatAlert) {
        val context = appContext ?: return
        val threatType = when (alert.type) {
            ThreatType.SILENT_SMS -> "Silent SMS Detected"
            ThreatType.ENCRYPTION_DISABLED,
            ThreatType.ENCRYPTION_DOWNGRADE -> "Weak Cipher Detected"
            ThreatType.IMSI_CATCHER -> "IMSI Catcher Activity"
            else -> alert.type.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
        }
        SecurityNotificationManager.notify(context, threatType, alert.message)
    }
    
    /**
     * Check if logcat detection is available
     */
    fun isLogcatDetectionAvailable(context: Context): Boolean {
        return LogcatService.hasPermission(context)
    }
    
    /**
     * Get the current logcat-based threat score
     */
    fun getCurrentThreatScore(): LogcatThreatScore? = cachedThreatScore
    
    /**
     * Get trust score modifier based on logcat analysis
     */
    fun getTrustScoreModifier(cid: Int?): Float {
        val score = cachedThreatScore ?: return 0f
        if (!score.hasThreat) return 0f
        
        var modifier = 0f
        
        if (score.encryptionScore > 0.5f) {
            modifier -= score.encryptionScore * ENCRYPTION_IMPACT
        }
        if (score.silentSmsScore > 0.3f) {
            modifier -= score.silentSmsScore * SILENT_SMS_IMPACT
        }
        if (score.identityScore > 0.5f) {
            modifier -= score.identityScore * IMSI_CATCHER_IMPACT
        }
modifier -= score.handoverScore * 0.15f
    modifier -= score.protocolScore * 0.1f

    return modifier.coerceIn(-0.5f, 0f)
}
    
    /**
     * Check if there are any critical threats for a specific tower
     */
    fun hasActiveThreatForTower(cid: Int): Boolean {
        val now = System.currentTimeMillis()
        val recentWindow = 60_000L
        
        return synchronized(recentAlerts) {
            recentAlerts.any { alert ->
                (alert.cid == cid || alert.cid == null) && 
                (now - alert.timestamp < recentWindow) &&
                alert.severity in setOf(ThreatSeverity.HIGH, ThreatSeverity.CRITICAL)
            }
        }
    }
    
    fun getLogcatReasons(): List<String> = cachedThreatScore?.reasons ?: emptyList()
    
    fun getRecentAlerts(): List<ThreatAlert> {
        return synchronized(recentAlerts) { recentAlerts.toList() }
    }
    
    fun hasEncryptionThreat(): Boolean = (cachedThreatScore?.encryptionScore ?: 0f) > 0.5f
    fun hasSilentSmsThreat(): Boolean = (cachedThreatScore?.silentSmsScore ?: 0f) > 0.3f
    fun hasImsiCatcherThreat(): Boolean = (cachedThreatScore?.identityScore ?: 0f) > 0.5f
    
    fun getThreatSummary(): String {
        val score = cachedThreatScore ?: return "No logcat analysis available"
        if (!score.hasThreat) return "No threats detected via logcat"
        
        val threats = mutableListOf<String>()
        if (score.encryptionScore > 0.5f) threats.add("⚠️ Encryption issue detected")
        if (score.silentSmsScore > 0.3f) {
            val count = synchronized(silentSmsHistory) { silentSmsHistory.size }
            threats.add("⚠️ Silent SMS activity ($count received)")
        }
        if (score.identityScore > 0.5f) threats.add("🚨 Possible IMSI catcher")
        if (score.handoverScore > 0.4f) threats.add("⚠️ Suspicious handovers")
        
        return threats.joinToString("\n").ifEmpty { "Minor anomalies detected" }
    }
    
    fun adjustTrustScore(originalTrustScore: Float, cid: Int?): Float {
        if (cachedThreatScore == null) return originalTrustScore
        val modifier = getTrustScoreModifier(cid)
        return (originalTrustScore + (modifier * 100f)).coerceIn(0f, 100f)
    }
    
    fun reset() {
        cachedThreatScore = null
        synchronized(recentAlerts) { recentAlerts.clear() }
        synchronized(silentSmsHistory) { silentSmsHistory.clear() }
        LogcatService.resetThreatAssessment()
    }
    
    fun setMaintenanceMode(durationMs: Long, reason: String) {
        LogcatService.setMaintenanceMode(durationMs, reason)
    }
}

