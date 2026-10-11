package com.cymatune.config

import android.content.Context
import android.content.SharedPreferences
import com.cymatune.util.DistanceEstimator

/**
 * Centralized application configuration management for production deployment
 * 
 * This class provides:
 * - Runtime configuration of detection thresholds
 * - Performance optimization settings
 * - Database and caching configuration
 * - Security and encryption settings
 * - Logging and monitoring configuration
 * - Feature flag management
 */
object AppConfiguration {
    
    // Configuration file name
    private const val PREF_FILE_NAME = "cymatune_lt_config"
    private const val KEY_CONFIG_VERSION = "config_version"
    private const val CONFIG_VERSION = "1.0"
    
    // Detection thresholds
    private const val KEY_CONFIDENCE_THRESHOLD = "confidence_threshold"
    private const val DEFAULT_CONFIDENCE_THRESHOLD = 0.7
    
    private const val KEY_SIGNAL_ANOMALY_THRESHOLD_STRONGER = "signal_anomaly_threshold_stronger"
    private const val DEFAULT_SIGNAL_ANOMALY_THRESHOLD_STRONGER = 15.0
    
    private const val KEY_SIGNAL_ANOMALY_THRESHOLD_WEAKER = "signal_anomaly_threshold_weaker"
    private const val DEFAULT_SIGNAL_ANOMALY_THRESHOLD_WEAKER = 25.0
    
    // Performance settings
    private const val KEY_MAX_DATABASE_CONNECTIONS = "max_db_connections"
    private const val DEFAULT_MAX_DATABASE_CONNECTIONS = 5
    
    private const val KEY_DETECTION_INTERVAL_MS = "detection_interval_ms"
    private const val DEFAULT_DETECTION_INTERVAL_MS = 30000 // 30 seconds
    
    private const val KEY_MAX_LOG_ENTRIES = "max_log_entries"
    private const val DEFAULT_MAX_LOG_ENTRIES = 1000
    
    // Database settings
    private const val KEY_DATABASE_ENCRYPTION_ENABLED = "database_encryption_enabled"
    private const val DEFAULT_DATABASE_ENCRYPTION_ENABLED = true
    
    private const val KEY_DATABASE_BACKUP_ENABLED = "database_backup_enabled"
    private const val DEFAULT_DATABASE_BACKUP_ENABLED = true
    
    // Security settings
    private const val KEY_SECURE_LOGGING_ENABLED = "secure_logging_enabled"
    private const val DEFAULT_SECURE_LOGGING_ENABLED = true
    
    private const val KEY_CRASH_REPORTING_ENABLED = "crash_reporting_enabled"
    private const val DEFAULT_CRASH_REPORTING_ENABLED = true
    
    // Feature flags
    private const val KEY_SMS_DETECTION_ENABLED = "sms_detection_enabled"
    private const val DEFAULT_SMS_DETECTION_ENABLED = true
    
    private const val KEY_PAGING_DETECTION_ENABLED = "paging_detection_enabled"
    private const val DEFAULT_PAGING_DETECTION_ENABLED = true
    
    private const val KEY_PROTOCOL_ANALYSIS_ENABLED = "protocol_analysis_enabled"
    private const val DEFAULT_PROTOCOL_ANALYSIS_ENABLED = true
    
    // Distance estimation configuration
    private const val KEY_DISTANCE_ESTIMATION_CONFIG = "distance_estimation_config"
    
    private lateinit var preferences: SharedPreferences
    
    /**
     * Initialize configuration with application context
     */
    fun initialize(context: Context) {
        preferences = context.getSharedPreferences(PREF_FILE_NAME, Context.MODE_PRIVATE)
        
        // Migrate configuration if needed
        migrateConfiguration()
        
        // Apply configuration defaults if not set
        applyDefaults()
    }
    
    /**
     * Migrate configuration between versions
     */
    private fun migrateConfiguration() {
        val currentVersion = preferences.getString(KEY_CONFIG_VERSION, "0.0")
        
        if (currentVersion != CONFIG_VERSION) {
            // Perform migration logic here if needed
            preferences.edit()
                .putString(KEY_CONFIG_VERSION, CONFIG_VERSION)
                .apply()
        }
    }
    
    /**
     * Apply default configuration values
     */
    private fun applyDefaults() {
        val editor = preferences.edit()
        
        // Detection thresholds
        if (!preferences.contains(KEY_CONFIDENCE_THRESHOLD)) {
            editor.putFloat(KEY_CONFIDENCE_THRESHOLD, DEFAULT_CONFIDENCE_THRESHOLD.toFloat())
        }
        
        if (!preferences.contains(KEY_SIGNAL_ANOMALY_THRESHOLD_STRONGER)) {
            editor.putFloat(KEY_SIGNAL_ANOMALY_THRESHOLD_STRONGER, DEFAULT_SIGNAL_ANOMALY_THRESHOLD_STRONGER.toFloat())
        }
        
        if (!preferences.contains(KEY_SIGNAL_ANOMALY_THRESHOLD_WEAKER)) {
            editor.putFloat(KEY_SIGNAL_ANOMALY_THRESHOLD_WEAKER, DEFAULT_SIGNAL_ANOMALY_THRESHOLD_WEAKER.toFloat())
        }
        
        // Performance settings
        if (!preferences.contains(KEY_MAX_DATABASE_CONNECTIONS)) {
            editor.putInt(KEY_MAX_DATABASE_CONNECTIONS, DEFAULT_MAX_DATABASE_CONNECTIONS)
        }
        
        if (!preferences.contains(KEY_DETECTION_INTERVAL_MS)) {
            editor.putInt(KEY_DETECTION_INTERVAL_MS, DEFAULT_DETECTION_INTERVAL_MS)
        }
        
        if (!preferences.contains(KEY_MAX_LOG_ENTRIES)) {
            editor.putInt(KEY_MAX_LOG_ENTRIES, DEFAULT_MAX_LOG_ENTRIES)
        }
        
        // Database settings
        if (!preferences.contains(KEY_DATABASE_ENCRYPTION_ENABLED)) {
            editor.putBoolean(KEY_DATABASE_ENCRYPTION_ENABLED, DEFAULT_DATABASE_ENCRYPTION_ENABLED)
        }
        
        if (!preferences.contains(KEY_DATABASE_BACKUP_ENABLED)) {
            editor.putBoolean(KEY_DATABASE_BACKUP_ENABLED, DEFAULT_DATABASE_BACKUP_ENABLED)
        }
        
        // Security settings
        if (!preferences.contains(KEY_SECURE_LOGGING_ENABLED)) {
            editor.putBoolean(KEY_SECURE_LOGGING_ENABLED, DEFAULT_SECURE_LOGGING_ENABLED)
        }
        
        if (!preferences.contains(KEY_CRASH_REPORTING_ENABLED)) {
            editor.putBoolean(KEY_CRASH_REPORTING_ENABLED, DEFAULT_CRASH_REPORTING_ENABLED)
        }
        
        // Feature flags
        if (!preferences.contains(KEY_SMS_DETECTION_ENABLED)) {
            editor.putBoolean(KEY_SMS_DETECTION_ENABLED, DEFAULT_SMS_DETECTION_ENABLED)
        }
        
        if (!preferences.contains(KEY_PAGING_DETECTION_ENABLED)) {
            editor.putBoolean(KEY_PAGING_DETECTION_ENABLED, DEFAULT_PAGING_DETECTION_ENABLED)
        }
        
        if (!preferences.contains(KEY_PROTOCOL_ANALYSIS_ENABLED)) {
            editor.putBoolean(KEY_PROTOCOL_ANALYSIS_ENABLED, DEFAULT_PROTOCOL_ANALYSIS_ENABLED)
        }
        
        editor.apply()
    }
    
    // Detection threshold configuration
    val confidenceThreshold: Double
        get() = preferences.getFloat(KEY_CONFIDENCE_THRESHOLD, DEFAULT_CONFIDENCE_THRESHOLD.toFloat()).toDouble()
    
    val signalAnomalyThresholdStronger: Double
        get() = preferences.getFloat(KEY_SIGNAL_ANOMALY_THRESHOLD_STRONGER, DEFAULT_SIGNAL_ANOMALY_THRESHOLD_STRONGER.toFloat()).toDouble()
    
    val signalAnomalyThresholdWeaker: Double
        get() = preferences.getFloat(KEY_SIGNAL_ANOMALY_THRESHOLD_WEAKER, DEFAULT_SIGNAL_ANOMALY_THRESHOLD_WEAKER.toFloat()).toDouble()
    
    // Performance configuration
    val maxDatabaseConnections: Int
        get() = preferences.getInt(KEY_MAX_DATABASE_CONNECTIONS, DEFAULT_MAX_DATABASE_CONNECTIONS)
    
    val detectionIntervalMs: Int
        get() = preferences.getInt(KEY_DETECTION_INTERVAL_MS, DEFAULT_DETECTION_INTERVAL_MS)
    
    val maxLogEntries: Int
        get() = preferences.getInt(KEY_MAX_LOG_ENTRIES, DEFAULT_MAX_LOG_ENTRIES)
    
    // Database configuration
    val isDatabaseEncryptionEnabled: Boolean
        get() = preferences.getBoolean(KEY_DATABASE_ENCRYPTION_ENABLED, DEFAULT_DATABASE_ENCRYPTION_ENABLED)
    
    val isDatabaseBackupEnabled: Boolean
        get() = preferences.getBoolean(KEY_DATABASE_BACKUP_ENABLED, DEFAULT_DATABASE_BACKUP_ENABLED)
    
    // Security configuration
    val isSecureLoggingEnabled: Boolean
        get() = preferences.getBoolean(KEY_SECURE_LOGGING_ENABLED, DEFAULT_SECURE_LOGGING_ENABLED)
    
    val isCrashReportingEnabled: Boolean
        get() = preferences.getBoolean(KEY_CRASH_REPORTING_ENABLED, DEFAULT_CRASH_REPORTING_ENABLED)
    
    // Feature flags
    val isSmsDetectionEnabled: Boolean
        get() = preferences.getBoolean(KEY_SMS_DETECTION_ENABLED, DEFAULT_SMS_DETECTION_ENABLED)
    
    val isPagingDetectionEnabled: Boolean
        get() = preferences.getBoolean(KEY_PAGING_DETECTION_ENABLED, DEFAULT_PAGING_DETECTION_ENABLED)
    
    val isProtocolAnalysisEnabled: Boolean
        get() = preferences.getBoolean(KEY_PROTOCOL_ANALYSIS_ENABLED, DEFAULT_PROTOCOL_ANALYSIS_ENABLED)
    
    /**
     * Distance estimation configuration
     */
    val distanceEstimationConfig: DistanceEstimator.DistanceEstimationConfig
        get() = DistanceEstimator.DistanceEstimationConfig(
            enableNativeOptimization = true,
            environmentDetectionEnabled = true,
            qualityWeightingEnabled = true,
            historicalAnalysisEnabled = true,
            anomalyDetectionThreshold = signalAnomalyThresholdStronger
        )
    
    /**
     * Update configuration value
     */
    fun updateConfiguration(key: String, value: Any) {
        val editor = preferences.edit()
        
        when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Float -> editor.putFloat(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Long -> editor.putLong(key, value)
            else -> throw IllegalArgumentException("Unsupported configuration type: ${value.javaClass}")
        }
        
        editor.apply()
    }
    
    /**
     * Reset configuration to defaults
     */
    fun resetToDefaults() {
        preferences.edit().clear().apply()
        applyDefaults()
    }
    
    /**
     * Get all configuration keys and values for debugging
     */
    fun getAllConfiguration(): Map<String, Any> {
        return preferences.all as Map<String, Any>
    }
    
    /**
     * Validate current configuration
     */
    fun validateConfiguration(): ValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        
        // Validate detection thresholds
        if (confidenceThreshold !in 0.0..1.0) {
            errors.add("Confidence threshold must be between 0.0 and 1.0")
        }
        
        if (signalAnomalyThresholdStronger <= 0) {
            errors.add("Signal anomaly threshold (stronger) must be positive")
        }
        
        if (signalAnomalyThresholdWeaker <= 0) {
            errors.add("Signal anomaly threshold (weaker) must be positive")
        }
        
        // Validate performance settings
        if (detectionIntervalMs < 1000) {
            warnings.add("Detection interval too low (< 1000ms) may cause battery drain")
        }
        
        if (maxDatabaseConnections < 1 || maxDatabaseConnections > 20) {
            warnings.add("Database connections should be between 1 and 20")
        }
        
        if (maxLogEntries < 100 || maxLogEntries > 10000) {
            warnings.add("Log entries should be between 100 and 10000")
        }
        
        return ValidationResult(errors, warnings)
    }
    
    /**
     * Configuration validation result
     */
    data class ValidationResult(
        val errors: List<String>,
        val warnings: List<String>
    ) {
        val isValid: Boolean = errors.isEmpty()
        val hasIssues: Boolean = errors.isNotEmpty() || warnings.isNotEmpty()
    }
}