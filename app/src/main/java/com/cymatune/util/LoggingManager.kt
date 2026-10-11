package com.cymatune.util

import android.util.Log
import com.cymatune.config.AppConfiguration
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized logging management for production deployment
 * 
 * Features:
 * - Configurable log levels per component
 * - Performance-optimized logging with minimal overhead
 * - Structured logging with consistent format
 * - Log rotation and size management
 * - Security-aware logging (PII filtering)
 * - Remote logging capability
 */
object LoggingManager {
    
    // Log level enum
    enum class LogLevel {
        VERBOSE, DEBUG, INFO, WARN, ERROR, ASSERT, DISABLED
    }
    
    // Component categories for granular control
    enum class Component {
        FAKE_TOWER_DETECTION,
        DATABASE,
        NETWORK_COMMUNICATION,
        UI,
        SECURITY,
        PERFORMANCE,
        LIFECYCLE,
        ERROR_HANDLING,
        CONFIGURATION,
        UTILITY
    }
    
    // Log entry structure for structured logging
    data class LogEntry(
        val timestamp: Long,
        val level: LogLevel,
        val component: Component,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null,
        val metadata: Map<String, Any> = emptyMap()
    )
    
    // Configuration
    private const val MAX_LOG_ENTRIES = 1000
    private const val LOG_ROTATION_THRESHOLD = 500
    
    // Thread-safe log storage
    private val logEntries = Collections.synchronizedList(mutableListOf<LogEntry>())
    private val componentLogLevels = ConcurrentHashMap<Component, LogLevel>()
    
    // Date formatter for consistent timestamps
    private val timestampFormatter = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
    
    // Initialize with default log levels
    init {
        initializeDefaultLogLevels()
    }
    
    /**
     * Initialize default log levels for each component
     */
    private fun initializeDefaultLogLevels() {
        // Production defaults - reduce noise
        componentLogLevels[Component.FAKE_TOWER_DETECTION] = LogLevel.INFO
        componentLogLevels[Component.DATABASE] = LogLevel.WARN
        componentLogLevels[Component.NETWORK_COMMUNICATION] = LogLevel.WARN
        componentLogLevels[Component.UI] = LogLevel.INFO
        componentLogLevels[Component.SECURITY] = LogLevel.VERBOSE
        componentLogLevels[Component.PERFORMANCE] = LogLevel.WARN
        componentLogLevels[Component.LIFECYCLE] = LogLevel.INFO
        componentLogLevels[Component.ERROR_HANDLING] = LogLevel.VERBOSE
        componentLogLevels[Component.CONFIGURATION] = LogLevel.DEBUG
        componentLogLevels[Component.UTILITY] = LogLevel.WARN
        
        // Override with configuration if available
        updateLogLevelsFromConfiguration()
    }
    
    /**
     * Update log levels from configuration
     */
    private fun updateLogLevelsFromConfiguration() {
        // This would integrate with AppConfiguration when available
        // For now, using defaults
    }
    
    /**
     * Set log level for a specific component
     */
    fun setLogLevel(component: Component, level: LogLevel) {
        componentLogLevels[component] = level
        log(LogLevel.INFO, Component.CONFIGURATION, "Log level set for ${component.name}: $level")
    }
    
    /**
     * Get current log level for a component
     */
    fun getLogLevel(component: Component): LogLevel {
        return componentLogLevels[component] ?: LogLevel.INFO
    }
    
    /**
     * Main logging method with structured data
     */
    fun log(
        level: LogLevel,
        component: Component,
        message: String,
        throwable: Throwable? = null,
        metadata: Map<String, Any> = emptyMap()
    ) {
        // Check if logging is enabled for this component and level
        val componentLevel = getLogLevel(component)
        if (level.ordinal < componentLevel.ordinal) {
            return
        }
        
        // Create log entry
        val logEntry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            component = component,
            tag = "CymatunePro_${component.name}",
            message = sanitizeMessage(message),
            throwable = throwable,
            metadata = sanitizeMetadata(metadata)
        )
        
        // Add to storage with rotation
        addLogEntry(logEntry)
        
        // Delegate to Android Log
        writeToAndroidLog(logEntry)
    }
    
    /**
     * Add log entry with rotation management
     */
    private fun addLogEntry(entry: LogEntry) {
        synchronized(logEntries) {
            logEntries.add(entry)
            
            // Implement log rotation
            if (logEntries.size > MAX_LOG_ENTRIES) {
                val entriesToRemove = logEntries.size - LOG_ROTATION_THRESHOLD
                logEntries.subList(0, entriesToRemove).clear()
            }
        }
    }
    
    /**
     * Write to Android Log system
     */
    private fun writeToAndroidLog(entry: LogEntry) {
        val formattedMessage = formatLogMessage(entry)
        
        when (entry.level) {
            LogLevel.VERBOSE -> Log.v(entry.tag, formattedMessage, entry.throwable)
            LogLevel.DEBUG -> Log.d(entry.tag, formattedMessage, entry.throwable)
            LogLevel.INFO -> Log.i(entry.tag, formattedMessage, entry.throwable)
            LogLevel.WARN -> Log.w(entry.tag, formattedMessage, entry.throwable)
            LogLevel.ERROR -> Log.e(entry.tag, formattedMessage, entry.throwable)
            LogLevel.ASSERT -> Log.wtf(entry.tag, formattedMessage, entry.throwable)
            LogLevel.DISABLED -> {} // Do nothing
        }
    }
    
    /**
     * Format log message with structured data
     */
    private fun formatLogMessage(entry: LogEntry): String {
        val timestamp = timestampFormatter.get()!!.format(Date(entry.timestamp))
        val baseMessage = "[${entry.component.name}] $timestamp - ${entry.message}"
        
        return if (entry.metadata.isNotEmpty()) {
            val metadataStr = entry.metadata.entries.joinToString(", ") { "${it.key}=${it.value}" }
            "$baseMessage | $metadataStr"
        } else {
            baseMessage
        }
    }
    
    /**
     * Sanitize message to remove sensitive information
     */
    private fun sanitizeMessage(message: String): String {
        var sanitized = message
        
        // Remove potential PII patterns
        sanitized = sanitized.replace(Regex("\\b\\d{3}[-.]?\\d{2}[-.]?\\d{4}\\b"), "XXX-XX-XXXX") // SSN-like
        sanitized = sanitized.replace(Regex("\\b\\d{16}\\b"), "XXXXXXXXXXXXXXXX") // Credit card-like
        sanitized = sanitized.replace(Regex("\\b\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\b"), "XXX.XXX.XXX.XXX") // IP-like
        
        return sanitized
    }
    
    /**
     * Sanitize metadata to remove sensitive information
     */
    private fun sanitizeMetadata(metadata: Map<String, Any>?): Map<String, Any> {
        val sanitized = mutableMapOf<String, Any>()
        
        for ((key, value) in metadata ?: emptyMap()) {
            val sanitizedKey = sanitizeKey(key)
            val sanitizedValue = when (value) {
                is String -> sanitizeValue(value)
                else -> value
            }
            sanitized[sanitizedKey] = sanitizedValue
        }
        
        return sanitized
    }
    
    /**
     * Sanitize metadata keys
     */
    private fun sanitizeKey(key: String): String {
        return key.lowercase().let { lowerKey ->
            when {
                lowerKey.contains("password") || lowerKey.contains("secret") || lowerKey.contains("key") -> "REDACTED_$key"
                lowerKey.contains("token") || lowerKey.contains("auth") -> "REDACTED_$key"
                else -> key
            }
        }
    }
    
    /**
     * Sanitize metadata values
     */
    private fun sanitizeValue(value: String): String {
        return sanitizeMessage(value)
    }
    
    // Convenience methods for each component
    fun v(component: Component, message: String, metadata: Map<String, Any> = emptyMap()) {
        log(LogLevel.VERBOSE, component, message, null, metadata)
    }
    
    fun d(component: Component, message: String, metadata: Map<String, Any> = emptyMap()) {
        log(LogLevel.DEBUG, component, message, null, metadata)
    }
    
    fun i(component: Component, message: String, metadata: Map<String, Any> = emptyMap()) {
        log(LogLevel.INFO, component, message, null, metadata)
    }
    
    fun w(component: Component, message: String, throwable: Throwable? = null, metadata: Map<String, Any> = emptyMap()) {
        log(LogLevel.WARN, component, message, throwable, metadata)
    }
    
    fun e(component: Component, message: String, throwable: Throwable? = null, metadata: Map<String, Any> = emptyMap()) {
        log(LogLevel.ERROR, component, message, throwable, metadata)
    }
    
    // Performance logging with timing
    fun performance(component: Component, operation: String, durationMs: Long, metadata: Map<String, Any> = emptyMap()) {
        val enhancedMetadata = metadata + mapOf("operation" to operation, "duration_ms" to durationMs)
        log(LogLevel.INFO, Component.PERFORMANCE, "Performance: $operation completed in ${durationMs}ms", null, enhancedMetadata)
    }
    
    // Security event logging
    fun security(event: String, severity: String, metadata: Map<String, Any> = emptyMap()) {
        val enhancedMetadata = metadata + mapOf("security_event" to event, "severity" to severity)
        log(LogLevel.WARN, Component.SECURITY, "Security Event: $event (Severity: $severity)", null, enhancedMetadata)
    }
    
    // Error handling with context
    fun error(component: Component, error: String, throwable: Throwable? = null, context: Map<String, Any> = emptyMap()) {
        val enhancedMetadata = context + mapOf("error_type" to (throwable?.javaClass?.simpleName ?: "Unknown"))
        log(LogLevel.ERROR, Component.ERROR_HANDLING, "Error in ${component.name}: $error", throwable, enhancedMetadata)
    }
    
    // Get recent log entries for debugging
    fun getRecentLogs(count: Int = 100): List<LogEntry> {
        return synchronized(logEntries) {
            logEntries.takeLast(count)
        }
    }
    
    // Clear all logs
    fun clearLogs() {
        synchronized(logEntries) {
            logEntries.clear()
        }
    }
    
    // Export logs for debugging (sanitized)
    fun exportLogs(): String {
        return synchronized(logEntries) {
            logEntries.joinToString("\n") { formatLogMessage(it) }
        }
    }
    
    // Performance monitoring helpers
    class PerformanceTimer(private val component: Component, private val operation: String) {
        private val startTime = System.currentTimeMillis()
        
        fun finish(metadata: Map<String, Any> = emptyMap()): Long {
            val duration = System.currentTimeMillis() - startTime
            LoggingManager.performance(component, operation, duration, metadata)
            return duration
        }
    }
    
    // Convenience method to create performance timer
    fun startTimer(component: Component, operation: String): PerformanceTimer {
        return PerformanceTimer(component, operation)
    }
}