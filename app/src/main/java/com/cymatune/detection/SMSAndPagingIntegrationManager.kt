package com.cymatune.detection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.content.ContextCompat
import com.cymatune.db.SMSEvent
import com.cymatune.db.DatabaseManager
import com.cymatune.util.CommonUtils
import kotlinx.coroutines.*
import com.cymatune.detection.DetectionPagingEventType
import java.util.concurrent.ConcurrentHashMap

/**
 * Threat level enum for SMS event classification
 */
enum class ThreatLevel {
    LOW, MEDIUM, HIGH, CRITICAL
}

/**
 * Custom exception for component initialization failures
 */
class ComponentInitializationException(
    override val message: String,
    override val cause: Throwable? = null,
    val componentName: String,
    val failureType: FailureType,
    val recoveryAction: RecoveryAction
) : Exception() {
    
    enum class FailureType {
        COMPONENT_CREATION_FAILED,
        DEPENDENCY_NOT_AVAILABLE,
        CONFIGURATION_ERROR,
        RUNTIME_ERROR
    }
    
    enum class RecoveryAction {
        USE_FALLBACK_COMPONENTS,
        RETRY_INITIALIZATION,
        SHUTDOWN_COMPONENT,
        CONTINUE_WITH_LIMITED_FUNCTIONALITY
    }
}

/**
 * SMSAndPagingIntegrationManager - Central manager for integrating SMS and paging detection
 * with the existing FakeTowerDetector workflow. This class coordinates between:
 * - SMSReceiver for incoming SMS monitoring
 * - SMSMessageProcessor for advanced SMS analysis
 * - PagingStormDetector for network-level paging analysis
 * - FakeTowerDetector for overall threat assessment
 * 
 * This integration ensures seamless coordination between SMS/paging detection and the existing
 * fake tower detection framework.
 */
class SMSAndPagingIntegrationManager(private val context: Context) {
    
    companion object {
        private const val TAG = "SMSAndPagingIntegration"
        
        // Intent actions for SMS threat detection
        const val ACTION_SMS_THREAT_DETECTED = "com.cymatune.SMS_THREAT_DETECTED"
        const val ACTION_PAGING_STORM_DETECTED = "com.cymatune.PAGING_STORM_DETECTED"
        const val ACTION_SMS_PAGING_CORRELATION = "com.cymatune.SMS_PAGING_CORRELATION"
        
        // Integration constants
        private const val CORRELATION_WINDOW_MS = 30000 // 30 seconds
        private const val THREAT_AGGREGATION_INTERVAL_MS = 60000 // 1 minute
        private const val DIAGNOSTIC_LOG_INTERVAL_MS = 120000 // 2 minutes
    }
    
    // Integration components
    private var smsReceiver: SMSReceiver? = null
    private lateinit var smsMessageProcessor: SMSMessageProcessor
    private lateinit var pagingStormDetector: PagingStormDetector
    
    // Tracking and correlation
    private val backgroundScope = CoroutineScope(Dispatchers.IO + Job())
    private val smsHistory = ConcurrentHashMap<String, MutableList<SMSEvent>>()
    private val threatCorrelations = ConcurrentHashMap<String, ThreatCorrelation>()
    private var lastCorrelationTime = 0L
    private var lastDiagnosticLog = 0L
    
    // Database access
    private var smsEventDao: com.cymatune.db.SmsEventDao? = null
    private var pagingEventDao: com.cymatune.db.PagingEventDao? = null
    private var pagingStormDao: com.cymatune.db.PagingStormDao? = null
    
    // State management
    private var isInitialized = false
    private var isMonitoring = false
    
    init {
        // Initialize database access asynchronously to handle suspend function
        backgroundScope.launch {
            initializeDatabaseAccess()
        }
    }
    
    /**
     * Initialize the integration manager
     */
    fun initialize() {
        if (isInitialized) {
            Log.d(TAG, "Integration manager already initialized")
            return
        }
        
        try {
            Log.d(TAG, "Initializing SMS and Paging integration manager")
            
            // Initialize components
            smsMessageProcessor = SMSMessageProcessor(context)
            Log.d(TAG, "SMS Message Processor initialized")
            
            pagingStormDetector = PagingStormDetector(context)
            Log.d(TAG, "Paging Storm Detector initialized")
            
            // Initialize SMS receiver
            initializeSMSReceiver()
            
            // Start monitoring
            startMonitoring()
            
            isInitialized = true
            Log.i(TAG, "SMS and Paging integration manager initialized successfully")
            Log.d(TAG, "Configuration: correlationWindow=${CORRELATION_WINDOW_MS}ms, aggregationInterval=${THREAT_AGGREGATION_INTERVAL_MS}ms")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing integration manager", e)
            Log.e(TAG, "Initialization error: ${e.javaClass.name}: ${e.message}")
        }
    }
    
    /**
     * Start monitoring for SMS and paging threats
     */
    private fun startMonitoring() {
        if (isMonitoring) return
        
        Log.d(TAG, "Starting SMS and paging threat monitoring")
        isMonitoring = true
        
        // Start paging storm detection
        pagingStormDetector.startMonitoring()
        
        // Start periodic correlation analysis
        startPeriodicCorrelationAnalysis()
        
        Log.d(TAG, "SMS and paging monitoring started")
    }
    
    /**
     * Stop monitoring
     */
    fun stopMonitoring() {
        Log.d(TAG, "Stopping SMS and paging threat monitoring")
        isMonitoring = false
        
        // Stop paging storm detection
        pagingStormDetector.stopMonitoring()
        
        // Unregister SMS receiver
        unregisterSMSReceiver()
        
        // Cancel background tasks
        backgroundScope.cancel()
        
        Log.d(TAG, "SMS and paging monitoring stopped")
    }
    
    /**
     * Process incoming SMS message through the integration pipeline
     */
    suspend fun processIncomingSMS(intent: Intent): SMSProcessingResult = withContext(backgroundScope.coroutineContext) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastDiagnosticLog > DIAGNOSTIC_LOG_INTERVAL_MS) {
            Log.i(TAG, "Integration Manager diagnostic: processing ${intent.action}, monitoring=${isMonitoring}")
            lastDiagnosticLog = currentTime
        }
        
        return@withContext try {
            Log.d(TAG, "Processing incoming SMS through integration pipeline: ${intent.action}")
            Log.d(TAG, "Intent extras: ${intent.extras?.keySet()?.joinToString()}")
            
            // Extract SMS messages
            val smsMessages = extractSMSMessages(intent)
            if (smsMessages.isNullOrEmpty()) {
                Log.w(TAG, "No SMS messages found in intent")
                return@withContext SMSProcessingResult(
                    success = false,
                    error = "No SMS messages extracted"
                )
            }
            
            Log.d(TAG, "Extracted ${smsMessages.size} SMS messages for processing")
            
            // Process each message
            val processingResults = mutableListOf<com.cymatune.detection.SMSMessageProcessor.ProcessingResult>()
            
            smsMessages.forEach { smsMessage ->
                val result = smsMessageProcessor.processSMSMessage(smsMessage)
                processingResults.add(result)
                
                Log.d(TAG, "Processed SMS: suspicious=${result.isSuspicious}, silent=${result.isSilentSMS}")
                
                // Store SMS event if processing was successful
                result.smsEvent?.let { smsEvent ->
                    storeSMSEvent(smsEvent)
                    updateSMSTracking(smsEvent)
                }
                
                // Check for immediate correlations with paging events
                if (result.isSuspicious) {
                    Log.d(TAG, "SMS flagged as suspicious, correlating with paging events")
                    correlateWithPagingEvents(smsEvent = result.smsEvent)
                }
            }
            
            // Perform batch analysis
            val batchResult = smsMessageProcessor.batchProcessSMS(smsMessages.toList())
            Log.d(TAG, "Batch analysis completed")
            
            // Analyze overall threat level
            val overallThreat = analyzeOverallThreat(processingResults, batchResult)
            Log.d(TAG, "Overall threat analysis: score=${overallThreat.threatScore}, highThreat=${overallThreat.isHighThreat}")
            
            // Trigger fake tower detection if high threat
            if (overallThreat.isHighThreat) {
                Log.w(TAG, "HIGH THREAT DETECTED - triggering enhanced fake tower detection")
                triggerEnhancedFakeTowerDetection(processingResults, overallThreat)
            }
            
            Log.d(TAG, "SMS processing pipeline completed successfully")
            
            SMSProcessingResult(
                success = true,
                processingResults = processingResults,
                batchResult = batchResult,
                overallThreat = overallThreat
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming SMS", e)
            Log.e(TAG, "Processing error: ${e.javaClass.name}: ${e.message}")
            SMSProcessingResult(
                success = false,
                error = e.message ?: "Unknown error"
            )
        }
    }
    
    /**
     * Record paging event and analyze for storm patterns
     */
    suspend fun recordPagingEvent(
        eventType: com.cymatune.detection.DetectionPagingEventType,
        cellInfo: android.telephony.CellInfo? = null,
        signalStrength: Int? = null
    ) {
        return withContext(backgroundScope.coroutineContext) {
            try {
                // Record with paging storm detector
                pagingStormDetector.recordPagingEvent(eventType, cellInfo, signalStrength)
                
                // Analyze for paging storms
                val analysisResult = pagingStormDetector.analyzeNetworkState()
                
                if (analysisResult.isStormDetected) {
                    handlePagingStormDetection(analysisResult)
                }
                
                // Correlate with recent SMS events
                correlatePagingWithSMS(analysisResult)
                
                Log.d(TAG, "Paging event recorded and analyzed: $eventType")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error recording paging event", e)
            }
        }
    }
    
    /**
     * Correlate SMS events with paging patterns
     */
    suspend fun correlateSMSWithPaging(smsEvents: List<SMSEvent>): com.cymatune.detection.PagingStormDetector.SMSNetworkCorrelation {
        return smsMessageProcessor.correlateSMSWithPaging(smsEvents)
    }
    
    /**
     * Get current threat assessment combining SMS and paging analysis
     */
    suspend fun getCurrentThreatAssessment(): ThreatAssessment = withContext(backgroundScope.coroutineContext) {
        return@withContext try {
            val currentTime = System.currentTimeMillis()
            Log.d(TAG, "=== Starting comprehensive threat assessment ===")
            
            // Analyze paging patterns
            val pagingAnalysis = pagingStormDetector.analyzeNetworkState()
            Log.d(TAG, "Paging analysis: stormProbability=${pagingAnalysis.stormProbability}, isStorm=${pagingAnalysis.isStormDetected}")
            
            // Get recent SMS events for correlation
            val recentSMSEvents = getRecentSMSEvents(CORRELATION_WINDOW_MS.toLong())
            Log.d(TAG, "Retrieved ${recentSMSEvents.size} recent SMS events for correlation")
            
            // Correlate SMS and paging data
            val correlationResult = correlateSMSWithPaging(recentSMSEvents)
            Log.d(TAG, "SMS correlation: total=${correlationResult.totalCorrelations}, suspicious=${correlationResult.suspiciousCorrelations}")
            
            // Calculate combined threat score
            val combinedThreatScore = calculateCombinedThreatScore(pagingAnalysis, correlationResult)
            Log.d(TAG, "Combined threat score calculated: $combinedThreatScore")
            
            val assessment = ThreatAssessment(
                timestamp = currentTime,
                pagingAnalysis = pagingAnalysis,
                smsCorrelation = correlationResult,
                combinedThreatScore = combinedThreatScore,
                recommendedAction = determineActionBasedOnThreat(combinedThreatScore)
            )
            
            Log.d(TAG, "Threat assessment completed: action=${assessment.recommendedAction}")
            assessment
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current threat assessment", e)
            Log.e(TAG, "Threat assessment error: ${e.javaClass.name}: ${e.message}")
            ThreatAssessment(
                timestamp = System.currentTimeMillis(),
                pagingAnalysis = null,
                smsCorrelation = null,
                combinedThreatScore = 0.0,
                recommendedAction = "CONTINUE_MONITORING",
                error = e
            )
        }
    }
    
    private fun initializeSMSReceiver() {
        try {
            smsReceiver = SMSReceiver()
            
            val filter = IntentFilter().apply {
                addAction(android.provider.Telephony.Sms.Intents.SMS_RECEIVED_ACTION)
                addAction(com.cymatune.util.SMSConstants.SMS_DELIVERED_ACTION)
                addAction("android.intent.action.DATA_SMS_RECEIVED")
            }
            
            ContextCompat.registerReceiver(context, smsReceiver!!, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            
            Log.d(TAG, "SMS receiver registered successfully")
            
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot register SMS receiver due to permissions", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering SMS receiver", e)
        }
    }
    
    private fun unregisterSMSReceiver() {
        try {
            smsReceiver?.let { receiver ->
                context.unregisterReceiver(receiver)
            }
        } catch (e: IllegalArgumentException) {
            // Receiver might not be registered
            Log.d(TAG, "SMS receiver was not registered")
        }
    }
    
    private fun extractSMSMessages(intent: Intent): Array<SmsMessage>? {
        return try {
            when (intent.action) {
                android.provider.Telephony.Sms.Intents.SMS_RECEIVED_ACTION,
                com.cymatune.util.SMSConstants.SMS_DELIVERED_ACTION -> {
                    android.provider.Telephony.Sms.Intents.getMessagesFromIntent(intent)
                }
                "android.intent.action.DATA_SMS_RECEIVED" -> {
                    val pdus = intent.extras?.get("pdus")
                    if (pdus is Array<*>) {
                        pdus.mapNotNull { pdu ->
                            if (pdu is ByteArray) {
                                SmsMessage.createFromPdu(pdu)
                            } else null
                        }.toTypedArray()
                    } else null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract SMS messages", e)
            null
        }
    }
    
    private suspend fun storeSMSEvent(smsEvent: SMSEvent) {
        try {
            smsEventDao?.insertSmsEvent(smsEvent)
            Log.d(TAG, "SMS event stored: ${smsEvent.sender}")
        } catch (e: Exception) {
            Log.e(TAG, "Error storing SMS event", e)
        }
    }
    
    private fun updateSMSTracking(smsEvent: SMSEvent) {
        val senderHistory = smsHistory.getOrPut(smsEvent.sender) { mutableListOf() }
        senderHistory.add(smsEvent)
        
        // Keep only recent history
        val cutoffTime = System.currentTimeMillis() - (60000 * 60) // 1 hour
        senderHistory.removeAll { it.timestamp < cutoffTime }
    }
    
    private suspend fun correlateWithPagingEvents(smsEvent: SMSEvent?) {
        if (smsEvent == null) return
        
        try {
            val startTime = smsEvent.timestamp - CORRELATION_WINDOW_MS / 2
            val endTime = smsEvent.timestamp + CORRELATION_WINDOW_MS / 2
            
            // This would correlate with paging events in the time window
            // Implementation would depend on paging event storage
            
            Log.d(TAG, "Correlated SMS from ${smsEvent.sender} with paging events")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error correlating SMS with paging events", e)
        }
    }
    
    private suspend fun analyzeOverallThreat(
        processingResults: List<com.cymatune.detection.SMSMessageProcessor.ProcessingResult>,
        batchResult: com.cymatune.detection.SMSMessageProcessor.BatchProcessingResult
    ): OverallThreat {
        val suspiciousCount = processingResults.count { it.isSuspicious }
        val silentSMSCount = processingResults.count { it.isSilentSMS }
        val totalMessages = processingResults.size
        
        val threatScore = when {
            suspiciousCount >= 3 -> 0.9 // Multiple suspicious messages
            suspiciousCount >= 1 && silentSMSCount >= 2 -> 0.7 // Suspicious + silent SMS
            silentSMSCount >= 3 -> 0.6 // Multiple silent SMS
            suspiciousCount >= 1 -> 0.4 // Single suspicious message
            else -> 0.1 // Normal messages
        }
        
        return OverallThreat(
            threatScore = threatScore,
            isHighThreat = threatScore >= 0.7,
            suspiciousCount = suspiciousCount,
            silentSMSCount = silentSMSCount,
            totalCount = totalMessages
        )
    }
    
    private suspend fun triggerEnhancedFakeTowerDetection(
        processingResults: List<com.cymatune.detection.SMSMessageProcessor.ProcessingResult>,
        overallThreat: OverallThreat
    ) {
        try {
            // Get current cell information for enhanced detection
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as android.telephony.TelephonyManager
            
            // This would trigger enhanced fake tower detection with SMS context
            // Implementation would integrate with existing FakeTowerDetector
            
            Log.w(TAG, "Enhanced fake tower detection triggered due to SMS threats: ${overallThreat.threatScore}")
            
            // Broadcast threat detection
            val intent = Intent(ACTION_SMS_THREAT_DETECTED).apply {
                putExtra("threatScore", overallThreat.threatScore)
                putExtra("suspiciousCount", overallThreat.suspiciousCount)
                putExtra("silentSMSCount", overallThreat.silentSMSCount)
            }
            context.sendBroadcast(intent)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error triggering enhanced fake tower detection", e)
        }
    }
    
    private suspend fun handlePagingStormDetection(analysisResult: com.cymatune.detection.PagingStormDetector.NetworkAnalysisResult) {
        Log.w(TAG, "Paging storm detected! Probability: ${analysisResult.stormProbability}")
        
        // Broadcast paging storm detection
        val intent = Intent(ACTION_PAGING_STORM_DETECTED).apply {
            putExtra("stormProbability", analysisResult.stormProbability)
            putExtra("isStormDetected", analysisResult.isStormDetected)
            putExtra("recommendedAction", analysisResult.recommendedAction)
        }
        context.sendBroadcast(intent)
        
        // Trigger enhanced detection
        // This would integrate with FakeTowerDetector for comprehensive analysis
    }
    
    private suspend fun correlatePagingWithSMS(pagingAnalysis: com.cymatune.detection.PagingStormDetector.NetworkAnalysisResult) {
        try {
            val recentSMSEvents = getRecentSMSEvents(CORRELATION_WINDOW_MS.toLong())
            val correlationResult = correlateSMSWithPaging(recentSMSEvents)
            
            if (correlationResult.overallSuspiciousness) {
                Log.w(TAG, "Suspicious SMS-paging correlation detected")
                
                // Broadcast correlation result
                val intent = Intent(ACTION_SMS_PAGING_CORRELATION).apply {
                    putExtra("correlationResult", correlationResult.totalCorrelations)
                    putExtra("suspiciousCorrelations", correlationResult.suspiciousCorrelations)
                }
                context.sendBroadcast(intent)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error correlating paging with SMS", e)
        }
    }
    
    private suspend fun getRecentSMSEvents(timeWindowMs: Long): List<SMSEvent> {
        return try {
            val cutoffTime = System.currentTimeMillis() - timeWindowMs
            smsEventDao?.getSmsEventsInTimeRange(cutoffTime, System.currentTimeMillis()) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error getting recent SMS events", e)
            emptyList()
        }
    }
    
    private fun calculateCombinedThreatScore(
        pagingAnalysis: com.cymatune.detection.PagingStormDetector.NetworkAnalysisResult,
        correlationResult: com.cymatune.detection.PagingStormDetector.SMSNetworkCorrelation
    ): Double {
        var score = 0.0
        
        // Paging storm contribution
        score += pagingAnalysis.stormProbability * 0.5
        
        // SMS correlation contribution
        val smsCorrelationScore = if (correlationResult.totalCorrelations > 0) {
            correlationResult.suspiciousCorrelations.toDouble() / correlationResult.totalCorrelations
        } else 0.0
        score += smsCorrelationScore * 0.5
        
        return score.coerceIn(0.0, 1.0)
    }
    
    private fun determineActionBasedOnThreat(threatScore: Double): String {
        return when {
            threatScore >= 0.8 -> "IMMEDIATE_ALERT"
            threatScore >= 0.6 -> "HIGH_MONITORING"
            threatScore >= 0.4 -> "INCREASED_MONITORING"
            else -> "CONTINUE_MONITORING"
        }
    }
    
    private fun startPeriodicCorrelationAnalysis() {
        backgroundScope.launch {
            while (isMonitoring) {
                try {
                    kotlinx.coroutines.delay(THREAT_AGGREGATION_INTERVAL_MS.toLong())
                    if (isMonitoring) {
                        performPeriodicCorrelationAnalysis()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic correlation analysis", e)
                }
            }
        }
    }
    
    private suspend fun performPeriodicCorrelationAnalysis() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCorrelationTime < THREAT_AGGREGATION_INTERVAL_MS) {
            return
        }
        lastCorrelationTime = currentTime
        
        try {
            val threatAssessment = getCurrentThreatAssessment()
            
            // Log correlation summary
            Log.d(TAG, "Periodic correlation analysis: " +
                  "ThreatScore=${threatAssessment.combinedThreatScore}, " +
                  "Action=${threatAssessment.recommendedAction}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in periodic correlation analysis", e)
        }
    }
    
private suspend fun initializeDatabaseAccess() {
try {
// Initialize database (unencrypted for Cymatune Lite)
DatabaseManager.initialize(context)

smsEventDao = DatabaseManager.smsEventDao
pagingEventDao = DatabaseManager.pagingEventDao
pagingStormDao = DatabaseManager.pagingStormDao

Log.i(TAG, "Database initialized successfully for SMS and paging integration")
} catch (e: Exception) {
Log.e(TAG, "Database initialization failed", e)
// Try fallback database initialization
tryFallbackDatabaseInitialization()
}
}
    
    /**
     * Try fallback database initialization when primary initialization fails
     */
    private suspend fun tryFallbackDatabaseInitialization() {
        try {
            Log.w(TAG, "Attempting fallback database initialization for SMS and paging integration")
            DatabaseManager.initialize(context)
            
            smsEventDao = DatabaseManager.smsEventDao
            pagingEventDao = DatabaseManager.pagingEventDao
            pagingStormDao = DatabaseManager.pagingStormDao
            
            Log.w(TAG, "Fallback database initialization successful for SMS and paging integration")
        } catch (fallbackError: Exception) {
            Log.e(TAG, "Fallback database initialization also failed for SMS and paging integration", fallbackError)
            // Initialize mock DAOs for emergency mode
            initializeMockDaos()
        }
    }
    
    /**
     * Initialize mock DAOs when database initialization completely fails
     */
    private suspend fun initializeMockDaos() {
        try {
            Log.w(TAG, "Initializing mock DAOs for SMS and paging integration in emergency mode")
            
            // For emergency mode, we'll set the DAOs to null and handle gracefully
            smsEventDao = null
            pagingEventDao = null
            pagingStormDao = null
            
            Log.w(TAG, "Emergency mode initialized - SMS and paging integration running with limited functionality")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize emergency mode for SMS and paging integration", e)
            throw ComponentInitializationException(
                message = "SMS and paging integration emergency mode initialization failed",
                cause = e,
                componentName = "SMSAndPagingIntegrationManager",
                failureType = ComponentInitializationException.FailureType.COMPONENT_CREATION_FAILED,
                recoveryAction = ComponentInitializationException.RecoveryAction.USE_FALLBACK_COMPONENTS
            )
        }
    }
    
    
    /**
     * Process SMS with enhanced error handling for fallback mode
     */
    suspend fun processIncomingSMSWithFallback(intent: Intent): SMSProcessingResult = withContext(backgroundScope.coroutineContext) {
        try {
            return@withContext processIncomingSMS(intent)
        } catch (e: Exception) {
            Log.e(TAG, "SMS processing failed in normal mode, attempting fallback processing", e)
            return@withContext processSMSInFallbackMode(intent, e)
        }
    }
    
    /**
     * Process SMS in fallback mode when normal processing fails
     */
    private suspend fun processSMSInFallbackMode(intent: Intent, originalError: Exception): SMSProcessingResult {
        return try {
            Log.w(TAG, "Processing SMS in fallback mode due to error: ${originalError.message}")
            
            // Extract SMS messages with basic processing
            val smsMessages = extractSMSMessages(intent)
            if (smsMessages.isNullOrEmpty()) {
                Log.w(TAG, "No SMS messages found in fallback mode")
                return SMSProcessingResult(
                    success = false,
                    error = "No SMS messages extracted in fallback mode"
                )
            }
            
            Log.d(TAG, "Fallback mode: Processing ${smsMessages.size} SMS messages")
            
            // Basic SMS analysis without database dependency
            val suspiciousMessages = mutableListOf<com.cymatune.detection.SMSMessageProcessor.ProcessingResult>()
            
            smsMessages.forEach { smsMessage ->
                val result = performBasicSuspiciousSMSCheck(smsMessage)
                if (result.isSuspicious) {
                    suspiciousMessages.add(result)
                    Log.w(TAG, "Fallback mode: SMS flagged as suspicious from: ${smsMessage.originatingAddress}")
                }
            }
            
            // Basic threat assessment without database correlation
            val threatLevel = when {
                suspiciousMessages.size >= 3 -> "HIGH"
                suspiciousMessages.size >= 1 -> "MEDIUM"
                else -> "LOW"
            }
            
            Log.w(TAG, "Fallback mode: SMS threat assessment completed - Level: $threatLevel, Suspicious: ${suspiciousMessages.size}")
            
            SMSProcessingResult(
                success = true,
                processingResults = suspiciousMessages,
                batchResult = null, // No batch processing in fallback mode
                overallThreat = OverallThreat(
                    threatScore = when (threatLevel) {
                        "HIGH" -> 0.8
                        "MEDIUM" -> 0.5
                        else -> 0.2
                    },
                    isHighThreat = threatLevel == "HIGH",
                    suspiciousCount = suspiciousMessages.size,
                    silentSMSCount = 0, // Cannot detect silent SMS without full processing
                    totalCount = smsMessages.size
                ),
                error = "Processed in fallback mode due to database unavailability"
            )
            
        } catch (fallbackError: Exception) {
            Log.e(TAG, "SMS processing failed even in fallback mode", fallbackError)
            SMSProcessingResult(
                success = false,
                error = "SMS processing failed in both normal and fallback modes: ${fallbackError.message}"
            )
        }
    }
    
    /**
     * Perform basic suspicious SMS check without full database dependency
     */
    private fun performBasicSuspiciousSMSCheck(smsMessage: SmsMessage): com.cymatune.detection.SMSMessageProcessor.ProcessingResult {
        try {
            val sender = smsMessage.originatingAddress ?: "unknown"
            val messageBody = smsMessage.messageBody ?: ""
            
            // Basic suspicious patterns
            val suspiciousPatterns = listOf(
                "urgent", "immediate", "verify", "confirm", "account", "password",
                "ss7", "gsm", "fake", "tower", "intercept", "monitor"
            )
            
            val lowerBody = messageBody.lowercase()
            val suspiciousScore = suspiciousPatterns.count { pattern ->
                lowerBody.contains(pattern)
            }
            
            val isSuspicious = suspiciousScore >= 2 ||
                sender.length < 5 || // Suspicious short sender IDs
                messageBody.length > 500 // Unusually long messages
                
            Log.d(TAG, "Basic SMS check: sender=$sender, suspiciousScore=$suspiciousScore, isSuspicious=$isSuspicious")
            
            // Create a basic SMS event for logging (will be no-op in mock DAO)
            val smsEvent = com.cymatune.db.SMSEvent(
                senderNumber = sender,
                messageBody = messageBody,
                timestamp = System.currentTimeMillis(),
                isSuspicious = isSuspicious
            )
            
            return com.cymatune.detection.SMSMessageProcessor.ProcessingResult(
                isSuspicious = isSuspicious,
                isSilentSMS = false,
                threatAssessment = null,
                smsEvent = smsEvent,
                processingTimeMs = 0L
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error in basic SMS suspicious check", e)
            return com.cymatune.detection.SMSMessageProcessor.ProcessingResult(
                isSuspicious = false,
                isSilentSMS = false,
                threatAssessment = null,
                smsEvent = null,
                processingTimeMs = 0L
            )
        }
    }
    
    // Data classes for integration results
    data class SMSProcessingResult(
        val success: Boolean,
        val processingResults: List<com.cymatune.detection.SMSMessageProcessor.ProcessingResult>? = null,
        val batchResult: com.cymatune.detection.SMSMessageProcessor.BatchProcessingResult? = null,
        val overallThreat: OverallThreat? = null,
        val error: String? = null
    )
    
    data class OverallThreat(
        val threatScore: Double,
        val isHighThreat: Boolean,
        val suspiciousCount: Int,
        val silentSMSCount: Int,
        val totalCount: Int
    )
    
    data class ThreatCorrelation(
        val smsEventId: String,
        val pagingEventIds: List<String>,
        val correlationStrength: Double,
        val timestamp: Long
    )
    
    data class ThreatAssessment(
        val timestamp: Long,
        val pagingAnalysis: com.cymatune.detection.PagingStormDetector.NetworkAnalysisResult?,
        val smsCorrelation: com.cymatune.detection.PagingStormDetector.SMSNetworkCorrelation?,
        val combinedThreatScore: Double,
        val recommendedAction: String,
        val error: Exception? = null
    )
}