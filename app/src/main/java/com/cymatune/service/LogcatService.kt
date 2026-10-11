package com.cymatune.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.cymatune.logcat.LogcatEventParser
import com.cymatune.logcat.LogcatThreatAssessor
import com.cymatune.logcat.LogcatThreatScore
import com.cymatune.logcat.ThreatAlert
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service to read system logcat logs via ADB-granted READ_LOGS permission.
 * Parses logs for RIL events and feeds them to threat assessment for enhanced detection.
 */
class LogcatService : Service() {

    companion object {
        const val TAG = "LogcatService"
        private const val MAX_LOG_BUFFER = 1000
        private const val RETRY_DELAY_MS = 3000L
        private const val MAX_RETRIES = 5
        
        // Broadcast actions
        const val ACTION_NEW_LOG_ENTRY = "com.cymatune.NEW_LOG_ENTRY"
        const val ACTION_RESTART_READER = "com.cymatune.RESTART_LOG_READER"
        const val EXTRA_LOG_MESSAGE = "log_message"
        // Tags to monitor for network insights
        private val INTERESTING_TAGS = setOf(
            "RIL", "RILC", "RILD", "Radio", "Telephony", "Phone", "LocalService",
            "ServiceState", "SignalStrength", "Network", "Modem", "QCRIL", 
            "Samsung", "Mtk", "Qcom", "Qmi", "SMS", "MMS", "IMS", "VoLTE", "USSD",
            "NAS", "RRC", "LTE", "NR", "GSM", "WCDMA", "Auth", "Security",
            "Cipher", "Encrypt", "Handover", "Reselect", "Identity",
            "CellBroadcast", "Sim", "Uicc", "Stk", "Icc", "Carrrier", "Data", "Voice", "Call"
        )
        
        // Tags to EXCLUDE - prevent self-parsing + common false positive sources
        private val EXCLUDED_TAGS = setOf(
            "cymatune", "LogcatService", "LogcatThreat", "LogcatEvent", 
            "LogcatDetection", "HomeFragment", "TowerDetails",
            "WindowManager", "Activity", "ViewRootImpl", "InputMethod",
            "TransitionRequest", "RemoteTransition", "BinderProxy"
        )
        
        // Global flow for UI components to observe raw logs
        private val _logFlow = MutableSharedFlow<String>(replay = 100)
        val logFlow: SharedFlow<String> = _logFlow.asSharedFlow()
        
        // Threat assessment flow
        private val threatAssessor = LogcatThreatAssessor()
        val threatFlow: Flow<LogcatThreatScore> = threatAssessor.threatFlow
        val alertFlow: Flow<ThreatAlert> = threatAssessor.alertFlow
        
        // Track if reader is actively working
        private val isReaderActive = AtomicBoolean(false)
        
        // Parser instance
        private val parser = LogcatEventParser()
        
        /**
         * Check if READ_LOGS permission is granted
         */
        fun hasPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_LOGS
            ) == PackageManager.PERMISSION_GRANTED
        }
        
        /**
         * Check if the log reader is currently active
         */
        fun isReaderRunning(): Boolean = isReaderActive.get()
        
    /**
     * Start or restart the log reader
     * @param force If true, always restart. If false, only start if not already running.
     */
    fun restartReader(context: Context, force: Boolean = false) {
        // Don't restart if already running and not forced
        if (!force && isReaderActive.get()) {
            Log.d(TAG, "Reader already active, skipping restart")
            return
        }

        // D05 FIX: Check READ_LOGS permission before starting to prevent warnings
        if (!hasPermission(context)) {
            Log.w(TAG, "Cannot start log reader: READ_LOGS permission not granted")
            return
        }

        val intent = Intent(context, LogcatService::class.java).apply {
            action = ACTION_RESTART_READER
        }
            context.startService(intent)
        }
        
        /**
         * Get current threat assessment
         */
        fun getCurrentThreatScore(): LogcatThreatScore = threatAssessor.getCurrentAssessment()
        
        /**
         * Reset the threat assessor (e.g., when user acknowledges threats)
         */
        fun resetThreatAssessment() {
            threatAssessor.reset()
            parser.reset()
        }
        
        /**
         * Set maintenance mode (e.g., during Airplane Mode toggle)
         * Classifies noise as System Events instead of Attacks.
         */
        fun setMaintenanceMode(durationMs: Long, reason: String) {
            threatAssessor.setMaintenanceMode(durationMs, reason)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRunning = AtomicBoolean(false)
    private var logReaderJob: Job? = null
    private var process: Process? = null
    private var retryCount = 0
    
    // Statistics
    private var totalLinesProcessed = 0L
    private var eventsDetected = 0L

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "LogcatService starting command, action: ${intent?.action}")
        
        // Handle restart request
        if (intent?.action == ACTION_RESTART_READER) {
            Log.i(TAG, "Restart requested - stopping current reader and restarting")
            stopLogReader()
            retryCount = 0
            parser.reset()
            startLogReader()
            return START_STICKY
        }
        
        if (!isRunning.getAndSet(true)) {
            startLogReader()
        }
        return START_STICKY
    }
    
    private fun stopLogReader() {
        logReaderJob?.cancel()
        logReaderJob = null
        process?.destroy()
        process = null
        isReaderActive.set(false)
        isRunning.set(false)
    }

    private fun startLogReader() {
        // D05 FIX: Double-check permission before starting reader
        if (!hasPermission(this@LogcatService)) {
            Log.w(TAG, "Cannot start log reader: READ_LOGS permission not granted")
            isRunning.set(false)
            isReaderActive.set(false)
            return
        }

        isRunning.set(true)
        logReaderJob = serviceScope.launch {
            Log.i(TAG, "Starting logcat reader (attempt ${retryCount + 1})...")

            _logFlow.emit("[INFO] Starting enhanced logcat reader...")
            _logFlow.emit("[INFO] Threat detection active: encryption, silent SMS, IMSI catcher")

            // Permission already checked above, but verify again for safety
            if (!hasPermission(this@LogcatService)) {
                Log.w(TAG, "READ_LOGS permission not granted")
                _logFlow.emit("[WARNING] READ_LOGS permission not granted.")
                _logFlow.emit("[INFO] Grant via ADB: adb shell pm grant com.cymatune android.permission.READ_LOGS")
                _logFlow.emit("[INFO] Then toggle System Logs again to start detection.")
                
                // Retry checking permission periodically
                if (retryCount < MAX_RETRIES) {
                    retryCount++
                    delay(RETRY_DELAY_MS)
                    if (hasPermission(this@LogcatService)) {
                        _logFlow.emit("[INFO] Permission detected! Starting reader...")
                        startLogReader()
                    } else {
                        startLogReader()
                    }
                }
                return@launch
            }
            
            try {
                _logFlow.emit("[INFO] Permission granted. Starting RIL log analysis...")
                
                // Use -b main,radio,system to capture RIL/Modem logs explicitly
                process = Runtime.getRuntime().exec("logcat -b main,radio,system -v time -T 100")
                val reader = BufferedReader(InputStreamReader(process!!.inputStream))
                
                isReaderActive.set(true)
                retryCount = 0
                
                var line: String? = null
                while (isActive && reader.readLine().also { line = it } != null) {
                    line?.let { logLine ->
                        totalLinesProcessed++
                        
                        if (isInterestingLog(logLine)) {
                            // Emit raw log for UI display
                            _logFlow.emit(logLine)
                            
                            // Parse for RIL events
                            val event = parser.parse(logLine)
                            if (event != null) {
                                eventsDetected++
                                Log.d(TAG, "Detected RIL event: ${event::class.simpleName}")
                                
                                // Feed to threat assessor
                                threatAssessor.processEvent(event)
                            }
                            
                            // Specific check for WAP Push / Binary SMS (Spyware Injection Vectors)
                            if (logLine.contains("WAP_PUSH_RECEIVED") || logLine.contains("WAP_PUSH_DELIVER")) {
                                Log.w(TAG, "DANGER: WAP Push detected - Potential Spyware Injection")
                                val wapEvent = com.cymatune.logcat.RilEvent.WapPush(
                                    timestamp = System.currentTimeMillis(),
                                    rawLine = logLine,
                                    source = "LogcatService",
                                    content = logLine
                                )
                                threatAssessor.processEvent(wapEvent)
                            }
                            
                            if (logLine.contains("DATA_SMS_RECEIVED") || logLine.contains("destPort")) {
                                Log.w(TAG, "DANGER: Binary SMS detected - Potential Exploit Payload")
                                val binarySmsEvent = com.cymatune.logcat.RilEvent.BinarySms(
                                    timestamp = System.currentTimeMillis(),
                                    rawLine = logLine,
                                    source = "LogcatService",
                                    content = logLine
                                )
                                threatAssessor.processEvent(binarySmsEvent)
                            }
                        }
                    }
                }
                
                Log.w(TAG, "Logcat reader loop exited")
                _logFlow.emit("[WARNING] Log reader stopped. Lines processed: $totalLinesProcessed, Events detected: $eventsDetected")
                isReaderActive.set(false)
                
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException reading logcat: ${e.message}")
                _logFlow.emit("[ERROR] SecurityException: ${e.message}")
                _logFlow.emit("[INFO] Try restarting the app after granting permission.")
                isReaderActive.set(false)
                
                if (retryCount < MAX_RETRIES) {
                    retryCount++
                    delay(RETRY_DELAY_MS)
                    startLogReader()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading logcat: ${e.message}")
                _logFlow.emit("[ERROR] Failed to read logcat: ${e.message}")
                isReaderActive.set(false)
                
                if (retryCount < MAX_RETRIES) {
                    retryCount++
                    delay(RETRY_DELAY_MS)
                    startLogReader()
                }
            }
        }
    }

    private fun isInterestingLog(line: String): Boolean {
        // CRITICAL: Exclude app's own logs to prevent feedback loop
        if (EXCLUDED_TAGS.any { line.contains(it, ignoreCase = true) }) {
            return false
        }
        return INTERESTING_TAGS.any { line.contains(it, ignoreCase = true) }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLogReader()
        serviceScope.cancel()
        Log.d(TAG, "LogcatService destroyed. Total lines: $totalLinesProcessed, Events: $eventsDetected")
    }
}

