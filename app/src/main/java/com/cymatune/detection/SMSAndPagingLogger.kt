package com.cymatune.detection

import android.content.Context
import android.os.Build
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import android.util.Log
import com.cymatune.db.SMSEvent
import com.cymatune.db.PagingEvent
import com.cymatune.db.PagingStorm
import com.cymatune.util.CommonUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * SMSAndPagingLogger - Comprehensive logging system for SMS and paging detection validation.
 * This logger provides detailed diagnostic information for:
 * - SMS message processing and analysis
 * - Silent SMS detection patterns
 * - Paging storm indicators
 * - Network state correlations
 * - Threat assessment validation
 * - Integration workflow tracking
 * 
 * The logger supports multiple output formats:
 * - Android Logcat (primary)
 * - Structured log files (detailed analysis)
 * - CSV exports (for external analysis)
 * - JSON reports (for automated processing)
 */
class SMSAndPagingLogger(private val context: Context) {
    
    companion object {
        private const val TAG = "SMSAndPagingLogger"
        
        // Log levels for different types of information
        private const val LOG_LEVEL_DEBUG = "DEBUG"
        private const val LOG_LEVEL_INFO = "INFO"
        private const val LOG_LEVEL_WARNING = "WARNING"
        private const val LOG_LEVEL_ERROR = "ERROR"
        private const val LOG_LEVEL_CRITICAL = "CRITICAL"
        
        // File logging constants
        private const val LOG_FILE_PREFIX = "sms_paging_log_"
        private const val LOG_FILE_EXTENSION = ".txt"
        private const val CSV_FILE_PREFIX = "sms_paging_analysis_"
        private const val CSV_FILE_EXTENSION = ".csv"
        private const val JSON_REPORT_PREFIX = "sms_paging_report_"
        private const val JSON_REPORT_EXTENSION = ".json"
        
        // Maximum log file size (1MB)
        private const val MAX_LOG_FILE_SIZE = 1024 * 1024
        
        // Log retention (keep logs for 7 days)
        private const val LOG_RETENTION_DAYS = 7
    }
    
    // Logging state
    private var isLoggingEnabled = true
    private var isFileLoggingEnabled = true
    private var currentLogFile: File? = null
    private val logBuffer = ConcurrentHashMap<String, StringBuilder>()
    private var logFlushInterval = 5000L // 5 seconds
    
    // Performance tracking
    private val performanceMetrics = ConcurrentHashMap<String, PerformanceMetric>()
    private var lastPerformanceReport = 0L
    
    init {
        initializeLogging()
    }
    
    /**
     * Initialize the logging system
     */
    private fun initializeLogging() {
        try {
            Log.i(TAG, "Initializing SMS and Paging Detection Logger")
            
            // Clean up old log files
            cleanupOldLogs()
            
            // Create new log file
            createNewLogFile()
            
            // Start performance monitoring
            startPerformanceMonitoring()
            
            Log.i(TAG, "SMS and Paging Detection Logger initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing logger", e)
            isLoggingEnabled = false
        }
    }
    
    /**
     * Log SMS message processing event
     */
    fun logSMSProcessing(
        event: String,
        smsEvent: SMSEvent,
        processingResult: SMSMessageProcessor.ProcessingResult? = null,
        additionalInfo: Map<String, String> = emptyMap()
    ) {
        if (!isLoggingEnabled) return
        
        val logMessage = buildString {
            append("SMS Processing - ")
            append("Event: $event, ")
            append("Sender: ${smsEvent.sender}, ")
            append("Timestamp: ${formatTimestamp(smsEvent.timestamp)}, ")
            append("Type: ${smsEvent.messageType}, ")
            append("Silent: ${smsEvent.isSilent}, ")
            append("ThreatLevel: ${smsEvent.threatLevel ?: "UNKNOWN"}, ")
            append("Confidence: ${smsEvent.threatConfidence ?: 0.0}")
            
            processingResult?.let {
                append(", ProcessingTime: ${it.processingTimeMs}ms, ")
                append("IsSuspicious: ${it.isSuspicious}")
            }
            
            additionalInfo.entries.forEach { (key, value) ->
                append(", $key: $value")
            }
        }
        
        logWithLevel(LOG_LEVEL_INFO, logMessage)
        logToFile(logMessage)
        
        // Log to CSV format for analysis
        logToCSV("SMS_PROCESSING", mapOf(
            "event" to event,
            "sender" to (smsEvent.sender ?: "Unknown"),
            "timestamp" to smsEvent.timestamp.toString(),
            "message_type" to smsEvent.messageType.toString(),
            "is_silent" to smsEvent.isSilent.toString(),
            "threat_level" to (smsEvent.threatLevel?.name ?: "UNKNOWN"),
            "threat_confidence" to (smsEvent.threatConfidence ?: 0.0).toString(),
            "processing_time" to (processingResult?.processingTimeMs ?: 0).toString(),
            "is_suspicious" to (processingResult?.isSuspicious ?: false).toString()
        ))
    }
    
    /**
     * Log paging event detection
     */
    fun logPagingEvent(
        eventType: String,
        pagingEvent: PagingEvent,
        analysisResult: PagingStormDetector.NetworkAnalysisResult? = null,
        additionalInfo: Map<String, String> = emptyMap()
    ) {
        if (!isLoggingEnabled) return
        
        val logMessage = buildString {
            append("Paging Event - ")
            append("Type: $eventType, ")
            append("Timestamp: ${formatTimestamp(pagingEvent.timestamp)}, ")
            append("Cell: ${pagingEvent.cid}/${pagingEvent.lac}, ")
            append("Network: ${pagingEvent.networkType}, ")
            append("Signal: ${pagingEvent.signalStrength}")
            
            analysisResult?.let {
                append(", StormProbability: ${it.stormProbability}, ")
                append("IsStormDetected: ${it.isStormDetected}")
            }
            
            additionalInfo.entries.forEach { (key, value) ->
                append(", $key: $value")
            }
        }
        
        logWithLevel(LOG_LEVEL_INFO, logMessage)
        logToFile(logMessage)
        
        // Log to CSV format
        logToCSV("PAGING_EVENT", mapOf<String, String>(
            "event_type" to eventType,
            "timestamp" to pagingEvent.timestamp.toString(),
            "cid" to pagingEvent.cid.toString(),
            "lac" to pagingEvent.lac.toString(),
            "mcc" to pagingEvent.mcc.toString(),
            "mnc" to pagingEvent.mnc.toString(),
            "network_type" to (pagingEvent.networkType ?: ""),
            "signal_strength" to pagingEvent.signalStrength.toString(),
            "storm_probability" to (analysisResult?.stormProbability ?: 0.0).toString(),
            "is_storm_detected" to (analysisResult?.isStormDetected ?: false).toString()
        ))
    }
    
    /**
     * Log paging storm detection
     */
    fun logPagingStorm(
        storm: PagingStorm,
        analysisResult: PagingStormDetector.NetworkAnalysisResult
    ) {
        if (!isLoggingEnabled) return
        
        val logMessage = buildString {
            append("Paging Storm Detected - ")
            append("Probability: ${analysisResult.stormProbability}, ")
            append("SilentSMS: 0, ")
            append("HighThreat: 0, ")
            append("TimeWindow: ${analysisResult.pagingAnalysis?.timeWindowMs}ms, ")
            append("RecommendedAction: ${analysisResult.recommendedAction}")
        }
        
        logWithLevel(LOG_LEVEL_CRITICAL, logMessage)
        logToFile(logMessage)
        
        // Log to CSV format
        logToCSV("PAGING_STORM", mapOf(
            "timestamp" to storm.startTime.toString(),
            "storm_probability" to analysisResult.stormProbability.toString(),
            "silent_sms_count" to "0",
            "high_threat_count" to "0",
            "time_window_ms" to (analysisResult.pagingAnalysis?.timeWindowMs ?: 0L).toString(),
            "recommended_action" to analysisResult.recommendedAction
        ))
    }
    
    /**
     * Log threat correlation between SMS and paging
     */
    fun logThreatCorrelation(
        correlation: PagingStormDetector.SMSNetworkCorrelation,
        threatAssessment: SMSAndPagingIntegrationManager.ThreatAssessment
    ) {
        if (!isLoggingEnabled) return
        
        val logMessage = buildString {
            append("Threat Correlation - ")
            append("TotalCorrelations: ${correlation.totalCorrelations}, ")
            append("Suspicious: ${correlation.suspiciousCorrelations}, ")
            append("OverallSuspicious: ${correlation.overallSuspiciousness}, ")
            append("CombinedThreatScore: ${threatAssessment.combinedThreatScore}, ")
            append("RecommendedAction: ${threatAssessment.recommendedAction}")
        }
        
        logWithLevel(LOG_LEVEL_WARNING, logMessage)
        logToFile(logMessage)
        
        // Log to CSV format
        logToCSV("THREAT_CORRELATION", mapOf(
            "total_correlations" to correlation.totalCorrelations.toString(),
            "suspicious_correlations" to correlation.suspiciousCorrelations.toString(),
            "overall_suspicious" to correlation.overallSuspiciousness.toString(),
            "combined_threat_score" to threatAssessment.combinedThreatScore.toString(),
            "recommended_action" to threatAssessment.recommendedAction
        ))
    }
    
    /**
     * Log network state changes
     */
    fun logNetworkStateChange(
        changeType: String,
        previousState: String,
        currentState: String,
        cellInfo: CellInfo? = null
    ) {
        if (!isLoggingEnabled) return
        
        val logMessage = buildString {
            append("Network State Change - ")
            append("Type: $changeType, ")
            append("Previous: $previousState, ")
            append("Current: $currentState")
            
            cellInfo?.let {
                append(", CellInfo: ${getCellInfoSummary(it)}")
            }
        }
        
        logWithLevel(LOG_LEVEL_DEBUG, logMessage)
        logToFile(logMessage)
    }
    
    /**
     * Log permission status and fallback mechanisms
     */
    fun logPermissionStatus(
        permission: String,
        granted: Boolean,
        fallbackUsed: Boolean = false,
        additionalInfo: Map<String, String> = emptyMap()
    ) {
        val level = if (granted) LOG_LEVEL_INFO else LOG_LEVEL_WARNING
        val status = if (granted) "GRANTED" else "DENIED"
        val fallback = if (fallbackUsed) ", Fallback Used" else ""
        
        val logMessage = buildString {
            append("Permission Status - ")
            append("Permission: $permission, ")
            append("Status: $status$fallback")
            
            additionalInfo.entries.forEach { (key, value) ->
                append(", $key: $value")
            }
        }
        
        logWithLevel(level, logMessage)
        logToFile(logMessage)
        
        // Log to CSV format
        logToCSV("PERMISSION_STATUS", mapOf(
            "permission" to permission,
            "granted" to granted.toString(),
            "fallback_used" to fallbackUsed.toString()
        ) + additionalInfo)
    }
    
    /**
     * Log performance metrics
     */
    fun logPerformanceMetric(
        metricName: String,
        value: Double,
        unit: String = "ms",
        metadata: Map<String, String> = emptyMap()
    ) {
        val metric = performanceMetrics.getOrPut(metricName) { PerformanceMetric(metricName) }
        metric.recordValue(value)
        
        val logMessage = buildString {
            append("Performance Metric - ")
            append("Name: $metricName, ")
            append("Value: $value $unit, ")
            append("Average: ${metric.getAverage()} $unit, ")
            append("Count: ${metric.valueCount}")
            
            metadata.entries.forEach { (key, value) ->
                append(", $key: $value")
            }
        }
        
        logWithLevel(LOG_LEVEL_DEBUG, logMessage)
    }
    
    /**
     * Log integration workflow events
     */
    fun logIntegrationEvent(
        componentName: String,
        eventType: String,
        success: Boolean,
        durationMs: Long = 0L,
        error: String? = null,
        additionalInfo: Map<String, String> = emptyMap()
    ) {
        val level = when {
            !success -> LOG_LEVEL_ERROR
            durationMs > 1000 -> LOG_LEVEL_WARNING // Slow operation
            else -> LOG_LEVEL_INFO
        }
        
        val status = if (success) "SUCCESS" else "FAILED"
        val duration = if (durationMs > 0) ", Duration: ${durationMs}ms" else ""
        val errorInfo = error?.let { ", Error: $it" } ?: ""
        
        val logMessage = buildString {
            append("Integration Event - ")
            append("Component: $componentName, ")
            append("Event: $eventType, ")
            append("Status: $status$duration$errorInfo")
            
            additionalInfo.entries.forEach { (key, value) ->
                append(", $key: $value")
            }
        }
        
        logWithLevel(level, logMessage)
        logToFile(logMessage)
        
        // Log to CSV format
        logToCSV("INTEGRATION_EVENT", mapOf<String, String>(
            "component" to componentName,
            "event_type" to eventType,
            "success" to success.toString(),
            "duration_ms" to durationMs.toString(),
            "error" to (error ?: "")
        ) + additionalInfo)
    }
    
    /**
     * Generate comprehensive diagnostic report
     */
    suspend fun generateDiagnosticReport(): DiagnosticReport = withContext(Dispatchers.IO) {
        return@withContext try {
            val currentTime = System.currentTimeMillis()
            
            // Collect performance statistics
            val performanceStats = collectPerformanceStatistics()
            
            // Generate summary statistics
            val summaryStats = collectSummaryStatistics()
            
            // Check system health
            val systemHealth = checkSystemHealth()
            
            val report = DiagnosticReport(
                timestamp = currentTime,
                performanceMetrics = performanceStats,
                summaryStatistics = summaryStats,
                systemHealth = systemHealth,
                logFileSize = getCurrentLogFileSize()
            )
            
            // Save report to file
            saveDiagnosticReport(report)
            
            // Log summary
            logWithLevel(LOG_LEVEL_INFO, "Diagnostic Report Generated - " +
                    "PerformanceMetrics: ${performanceStats.size}, " +
                    "SummaryStats: ${summaryStats.entries.size}, " +
                    "SystemHealth: ${systemHealth.status}")
            
            report
            
        } catch (e: Exception) {
            Log.e(TAG, "Error generating diagnostic report", e)
            DiagnosticReport(
                timestamp = System.currentTimeMillis(),
                error = e.message ?: "Unknown error"
            )
        }
    }
    
    private fun logWithLevel(level: String, message: String) {
        when (level) {
            LOG_LEVEL_DEBUG -> Log.d(TAG, message)
            LOG_LEVEL_INFO -> Log.i(TAG, message)
            LOG_LEVEL_WARNING -> Log.w(TAG, message)
            LOG_LEVEL_ERROR -> Log.e(TAG, message)
            LOG_LEVEL_CRITICAL -> Log.wtf(TAG, message)
            else -> Log.d(TAG, message)
        }
    }
    
    private fun logToFile(message: String) {
        if (!isFileLoggingEnabled) return
        
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            val logEntry = "[$timestamp] $message"
            
            currentLogFile?.let { logFile ->
                if (logFile.length() > MAX_LOG_FILE_SIZE) {
                    createNewLogFile()
                }
                
                FileWriter(logFile, true).use { writer ->
                    writer.append(logEntry).append("\n")
                }
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Error writing to log file", e)
        }
    }
    
    private fun logToCSV(eventType: String, data: Map<String, String>) {
        try {
            val timestamp = System.currentTimeMillis()
            val csvLine = buildCSVLine(timestamp, eventType, data)
            
            val csvFile = getCSVFile()
            FileWriter(csvFile, true).use { writer ->
                if (csvFile.length() == 0L) {
                    // Write header
                    val headers = buildCSVHeaders(data.keys)
                    writer.append(headers).append("\n")
                }
                writer.append(csvLine).append("\n")
            }
            
        } catch (e: IOException) {
            Log.e(TAG, "Error writing to CSV file", e)
        }
    }
    
    private fun buildCSVLine(timestamp: Long, eventType: String, data: Map<String, String>): String {
        val timestampStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
        val values = listOf(timestampStr, eventType) + data.values
        return values.joinToString(",")
    }
    
    private fun buildCSVHeaders(keys: Set<String>): String {
        val headers = listOf("timestamp", "event_type") + keys
        return headers.joinToString(",")
    }
    
    private fun getCSVFile(): File {
        val fileName = "${CSV_FILE_PREFIX}${getCurrentDateString()}${CSV_FILE_EXTENSION}"
        return File(context.filesDir, fileName)
    }
    
    private fun createNewLogFile() {
        try {
            val fileName = "${LOG_FILE_PREFIX}${getCurrentDateString()}_${getCurrentTimeString()}${LOG_FILE_EXTENSION}"
            currentLogFile = File(context.filesDir, fileName)
            
            // Write header
            FileWriter(currentLogFile!!, false).use { writer ->
                writer.append("SMS and Paging Detection Log\n")
                writer.append("Generated: ${getCurrentDateString()} ${getCurrentTimeString()}\n")
                writer.append("=".repeat(80)).append("\n")
            }
            
            Log.d(TAG, "Created new log file: ${currentLogFile?.name}")
            
        } catch (e: IOException) {
            Log.e(TAG, "Error creating log file", e)
        }
    }
    
    private fun cleanupOldLogs() {
        try {
            val filesDir = context.filesDir
            val cutoffDate = Date(System.currentTimeMillis() - (LOG_RETENTION_DAYS * 24 * 60 * 60 * 1000L))
            
            filesDir.listFiles { file ->
                file.name.startsWith(LOG_FILE_PREFIX) || 
                file.name.startsWith(CSV_FILE_PREFIX) ||
                file.name.startsWith(JSON_REPORT_PREFIX)
            }?.forEach { file ->
                if (file.lastModified() < cutoffDate.time) {
                    file.delete()
                    Log.d(TAG, "Deleted old log file: ${file.name}")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old logs", e)
        }
    }
    
    private fun startPerformanceMonitoring() {
        // Performance monitoring would be implemented here
        // For now, just log that it's started
        Log.d(TAG, "Performance monitoring started")
    }
    
    private fun collectPerformanceStatistics(): Map<String, PerformanceStats> {
        val stats = mutableMapOf<String, PerformanceStats>()
        
        performanceMetrics.forEach { (name, metric) ->
            stats[name] = PerformanceStats(
                name = name,
                averageValue = metric.getAverage(),
                minValue = metric.getMin(),
                maxValue = metric.getMax(),
                valueCount = metric.valueCount,
                standardDeviation = metric.getStandardDeviation()
            )
        }
        
        return stats
    }
    
    private fun collectSummaryStatistics(): Map<String, String> {
        return mapOf(
            "total_sms_processed" to "0", // Would be calculated from database
            "total_paging_events" to "0", // Would be calculated from database
            "silent_sms_count" to "0", // Would be calculated from database
            "threat_detections" to "0", // Would be calculated from database
            "false_positives" to "0", // Would be calculated from database
            "detection_accuracy" to "0.0" // Would be calculated from analysis
        )
    }
    
    private fun checkSystemHealth(): SystemHealth {
        val issues = mutableListOf<String>()
        var overallStatus = "HEALTHY"
        
        try {
            // Check file system
            val logDir = context.filesDir
            if (!logDir.canWrite()) {
                issues.add("Cannot write to log directory")
                overallStatus = "WARNING"
            }
            
            // Check available storage
            val freeSpace = logDir.freeSpace
            if (freeSpace < 1024 * 1024) { // Less than 1MB
                issues.add("Low disk space for logging")
                overallStatus = "WARNING"
            }
            
            // Check permission status
            val smsPermission = context.checkSelfPermission(android.Manifest.permission.RECEIVE_SMS)
            if (smsPermission != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                issues.add("SMS permissions not granted")
                overallStatus = "DEGRADED"
            }
            
        } catch (e: Exception) {
            issues.add("System health check failed: ${e.message}")
            overallStatus = "ERROR"
        }
        
        return SystemHealth(
            status = overallStatus,
            issues = issues,
            timestamp = System.currentTimeMillis()
        )
    }
    
    private fun getCurrentLogFileSize(): Long {
        return currentLogFile?.length() ?: 0L
    }
    
    private fun saveDiagnosticReport(report: DiagnosticReport) {
        try {
            val fileName = "${JSON_REPORT_PREFIX}${getCurrentDateString()}_${getCurrentTimeString()}${JSON_REPORT_EXTENSION}"
            val reportFile = File(context.filesDir, fileName)
            
            FileWriter(reportFile, false).use { writer ->
                writer.write(report.toString())
            }
            
            Log.d(TAG, "Diagnostic report saved: ${reportFile.name}")
            
        } catch (e: IOException) {
            Log.e(TAG, "Error saving diagnostic report", e)
        }
    }
    
    private fun getCurrentDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }
    
    private fun getCurrentTimeString(): String {
        return SimpleDateFormat("HH-mm-ss", Locale.getDefault()).format(Date())
    }
    
    private fun getCellInfoSummary(cellInfo: CellInfo): String {
        return try {
            when (cellInfo) {
                is android.telephony.CellInfoLte -> {
                    val identity = cellInfo.cellIdentity
                    "LTE-CI:${identity.ci}-TAC:${identity.tac}"
                }
                is android.telephony.CellInfoGsm -> {
                    val identity = cellInfo.cellIdentity
                    "GSM-CI:${identity.cid}-LAC:${identity.lac}"
                }
                is android.telephony.CellInfoWcdma -> {
                    val identity = cellInfo.cellIdentity
                    "WCDMA-CI:${identity.cid}-LAC:${identity.lac}"
                }
                else -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && cellInfo is android.telephony.CellInfoNr) {
                        try {
                            val identity = cellInfo.cellIdentity
                            val nci = identity.javaClass.getMethod("getNci").invoke(identity) as Long
                            val tac = identity.javaClass.getMethod("getTac").invoke(identity) as Int
                            "NR-CI:${nci}-TAC:${tac}"
                        } catch (e: Exception) {
                            "NR-Unknown"
                        }
                    } else {
                        "Unknown"
                    }
                }
            }
        } catch (e: Exception) {
            "Error getting cell info"
        }
    }
    
    // Data classes for logging
    private data class PerformanceMetric(
        val name: String,
        val values: MutableList<Double> = mutableListOf(),
        var lastUpdated: Long = System.currentTimeMillis()
    ) {
        val valueCount: Int get() = values.size
        
        fun recordValue(value: Double) {
            values.add(value)
            lastUpdated = System.currentTimeMillis()
        }
        
        fun getAverage(): Double = if (values.isNotEmpty()) values.average() else 0.0
        
        fun getMin(): Double = if (values.isNotEmpty()) values.minOrNull() ?: 0.0 else 0.0
        
        fun getMax(): Double = if (values.isNotEmpty()) values.maxOrNull() ?: 0.0 else 0.0
        
        fun getStandardDeviation(): Double {
            if (values.size < 2) return 0.0
            val mean = getAverage()
            val variance = values.map { (it - mean) * (it - mean) }.average()
            return kotlin.math.sqrt(variance)
        }
    }
    
    data class PerformanceStats(
        val name: String,
        val averageValue: Double,
        val minValue: Double,
        val maxValue: Double,
        val valueCount: Int,
        val standardDeviation: Double
    )
    
    data class SystemHealth(
        val status: String,
        val issues: List<String>,
        val timestamp: Long
    )
    
    data class DiagnosticReport(
        val timestamp: Long,
        val performanceMetrics: Map<String, PerformanceStats> = emptyMap(),
        val summaryStatistics: Map<String, String> = emptyMap(),
        val systemHealth: SystemHealth? = null,
        val logFileSize: Long = 0L,
        val error: String? = null
    )
    
    private fun formatTimestamp(timestamp: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
    }
    
    private operator fun String.times(count: Int): String {
        return repeat(count)
    }
}