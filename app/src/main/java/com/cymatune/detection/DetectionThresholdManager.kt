package com.cymatune.detection

import android.util.Log

/**
 * DetectionThresholdManager - Phase 3 Feature
 *
 * Centralized manager for detection sensitivity thresholds.
 * Provides "Strict Mode" toggle that adjusts all detector thresholds
 * for higher sensitivity detection.
 *
 * Usage:
 * - Call DetectionThresholdManager.setStrictMode(true/false) from SettingsFragment
 * - Access thresholds via DetectionThresholdManager.getThresholds()
 * - All detectors should query this manager for their thresholds
 */
object DetectionThresholdManager {

    private const val TAG = "DetectionThresholdManager"

    // Current mode
    @Volatile
    private var isStrictMode: Boolean = false

    // Threshold configurations
    data class ThresholdConfig(
        val signalStrengthAnomaly: Int,      // dBm threshold
        val lacChangeMinSuspicious: Int,      // Minimum suspicious LAC changes
        val pagingStormThreshold: Int,        // Events per minute
        val protocolHandshakeMultiplier: Double, // Handshake duration multiplier
        val neighborConsistencyThreshold: Double, // Jaccard index threshold
        val geographicAnomalyDistance: Double,  // Meters
        val rapidLacChangeTimeMs: Long,        // Time between changes (ms)
        val minObservationCount: Int           // Min observations before alerting
    )

    // Standard thresholds (balanced detection)
    private val STANDARD_THRESHOLDS = ThresholdConfig(
        signalStrengthAnomaly = -50,           // Flag signals stronger than -50 dBm
        lacChangeMinSuspicious = 3,             // Flag 3+ rapid LAC changes
        pagingStormThreshold = 15,              // Flag 15+ events/minute (filter background apps)
        protocolHandshakeMultiplier = 1.0,      // Standard handshake duration
        neighborConsistencyThreshold = 0.2,   // Jaccard index 20%
        geographicAnomalyDistance = 40000.0,    // 40km
        rapidLacChangeTimeMs = 300000L,         // 5 minutes
        minObservationCount = 3                 // Require 3 observations
    )

    // Strict thresholds (high sensitivity)
    private val STRICT_THRESHOLDS = ThresholdConfig(
        signalStrengthAnomaly = -60,           // Flag signals stronger than -60 dBm
        lacChangeMinSuspicious = 2,             // Flag 2+ rapid LAC changes
        pagingStormThreshold = 8,               // Flag 8+ events/minute
        protocolHandshakeMultiplier = 0.8,      // Lower handshake tolerance
        neighborConsistencyThreshold = 0.3,     // Jaccard index 30%
        geographicAnomalyDistance = 25000.0,    // 25km
        rapidLacChangeTimeMs = 600000L,         // 10 minutes (broader window)
        minObservationCount = 2                 // Require 2 observations
    )

    /**
     * Enable or disable strict mode
     * @param strict true for high sensitivity, false for standard
     */
    @JvmStatic
    fun setStrictMode(strict: Boolean) {
        isStrictMode = strict
        Log.i(TAG, "Detection thresholds set to: ${if (strict) "STRICT" else "STANDARD"}")
    }

    /**
     * Check if strict mode is enabled
     */
    @JvmStatic
    fun isStrictMode(): Boolean = isStrictMode

    /**
     * Get current threshold configuration
     */
    @JvmStatic
    fun getThresholds(): ThresholdConfig {
        return if (isStrictMode) STRICT_THRESHOLDS else STANDARD_THRESHOLDS
    }

    /**
     * Get signal strength threshold
     */
    @JvmStatic
    fun getSignalStrengthThreshold(): Int = getThresholds().signalStrengthAnomaly

    /**
     * Get LAC change threshold
     */
    @JvmStatic
    fun getLacChangeThreshold(): Int = getThresholds().lacChangeMinSuspicious

    /**
     * Get paging storm threshold
     */
    @JvmStatic
    fun getPagingStormThreshold(): Int = getThresholds().pagingStormThreshold

    /**
     * Get protocol handshake multiplier
     */
    @JvmStatic
    fun getProtocolHandshakeMultiplier(): Double = getThresholds().protocolHandshakeMultiplier

    /**
     * Get neighbor consistency threshold (Jaccard index)
     */
    @JvmStatic
    fun getNeighborConsistencyThreshold(): Double = getThresholds().neighborConsistencyThreshold

    /**
     * Get geographic anomaly distance (meters)
     */
    @JvmStatic
    fun getGeographicAnomalyDistance(): Double = getThresholds().geographicAnomalyDistance

    /**
     * Get rapid LAC change time window (ms)
     */
    @JvmStatic
    fun getRapidLacChangeTimeMs(): Long = getThresholds().rapidLacChangeTimeMs

    /**
     * Get minimum observation count before alerting
     */
    @JvmStatic
    fun getMinObservationCount(): Int = getThresholds().minObservationCount

    /**
     * Apply movement multiplier to a threshold
     * B09-B12: Movement-aware threshold adjustment
     */
    @JvmStatic
    fun applyMovementMultiplier(
        baseThreshold: Double,
        intensity: MovementDetector.MovementIntensity
    ): Double {
        val movementMultiplier = when (intensity) {
            MovementDetector.MovementIntensity.STATIONARY -> 1.0
            MovementDetector.MovementIntensity.WALKING -> 1.2
            MovementDetector.MovementIntensity.MOVING -> 1.5
            MovementDetector.MovementIntensity.TRAVELING -> 2.0
        }

        // In strict mode, reduce movement tolerance
        val strictMultiplier = if (isStrictMode) 0.7 else 1.0

        return baseThreshold * movementMultiplier * strictMultiplier
    }
}
