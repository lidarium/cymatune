package com.cymatune.util

import kotlin.math.pow
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.atan2
import android.util.Log

/**
 * Distance estimation utilities for calculating distances to cell towers using various signal metrics.
 *
 * This object provides multiple algorithms for distance estimation:
 * - Timing Advance (TA) based distance calculation
 * - RSRP-based path loss distance estimation
 * - Environment-aware signal propagation modeling
 * - Historical signal pattern analysis for anomaly detection
 */
object DistanceEstimator {

    // Special value to represent unknown distance
    const val UNKNOWN_DISTANCE = -1.0

    // LTE Timing Advance (TA) to distance conversion constant (meters per TA unit)
    // 1 TA unit = 0.52 microseconds round trip time. Speed of light = 3 * 10^8 m/s.
    // Distance = (TA * 0.52 * 10^-6 * 3 * 10^8) / 2 = TA * 78.125 meters
    private const val METERS_PER_TA_UNIT = 78.125 // meters

    // Environment-specific RSRP to distance model constants
    // Path Loss Model: L = L0 + 10 * n * log10(d/d0)
    // RSRP (dBm) = TxPower (dBm) - L (dB)
    // So, d = d0 * 10^((TxPower - RSRP - L0) / (10 * n))
    
    // Urban environment (dense buildings, high signal attenuation)
    private const val URBAN_TX_POWER_DBM = 43.0 // Typical urban macro cell power
    private const val URBAN_L0_DB = 132.0 // Higher path loss in urban areas
    private const val URBAN_PATH_LOSS_EXPONENT_N = 4.2 // Steeper signal decay
    
    // Suburban environment (moderate buildings, medium attenuation)
    private const val SUBURBAN_TX_POWER_DBM = 46.0 // Higher power for wider coverage
    private const val SUBURBAN_L0_DB = 128.0 // Medium path loss
    private const val SUBURBAN_PATH_LOSS_EXPONENT_N = 3.8 // Moderate signal decay
    
    // Rural environment (open areas, low attenuation)
    private const val RURAL_TX_POWER_DBM = 49.0 // Highest power for long range
    private const val RURAL_L0_DB = 122.0 // Lower path loss in open areas
    private const val RURAL_PATH_LOSS_EXPONENT_N = 3.2 // Gentler signal decay
    
    // Default values (urban as conservative default)
    private const val DEFAULT_TX_POWER_DBM = URBAN_TX_POWER_DBM
    private const val DEFAULT_L0_DB = URBAN_L0_DB
    private const val DEFAULT_D0_METERS = 1000.0 // Reference distance for L0 (1 km)
    private const val DEFAULT_PATH_LOSS_EXPONENT_N = URBAN_PATH_LOSS_EXPONENT_N
    
    // Historical signal analysis constants
    private const val MIN_HISTORICAL_SAMPLES = 5 // Minimum samples needed for historical analysis
    private const val SIGNAL_DEVIATION_THRESHOLD = 8.0 // Standard deviations for anomaly detection
    
    // Climate and seasonal adjustment factors (multipliers for path loss exponent)
    // These could be made configurable based on location/climate data
    private const val CLIMATE_TROPICAL_MULTIPLIER = 1.1 // Higher humidity increases attenuation
    private const val CLIMATE_DESERT_MULTIPLIER = 0.95 // Lower humidity reduces attenuation
    private const val CLIMATE_POLAR_MULTIPLIER = 1.05 // Cold air affects propagation
    private const val SEASON_SUMMER_MULTIPLIER = 1.05 // Foliage and heat effects
    private const val SEASON_WINTER_MULTIPLIER = 0.95 // Less foliage, different atmospheric conditions

    // Earth's radius in meters
    private const val EARTH_RADIUS = 6371000.0

    /**
     * Estimates the distance to a cell tower using Timing Advance (TA), Reference Signal Received Power (RSRP),
     * Reference Signal Received Quality (RSRQ), and Signal to Interference plus Noise Ratio (SINR).
     * Prioritizes TA for directness, uses RSRP as a fallback or for refinement, and incorporates RSRQ/SINR for quality weighting.
     *
     * @param ta Timing Advance value (0-1282 for LTE, corresponds to 0-100km approx)
     * @param rsrp Reference Signal Received Power in dBm (e.g., -120 to -40 dBm)
     * @param rsrq Reference Signal Received Quality in dB (e.g., -20 to -3 dB)
     * @param sinr Signal to Interference plus Noise Ratio in dB (e.g., -10 to 30 dB)
     * @return Estimated distance in meters, or null if no valid parameters are available.
     */
    // Load native library for optimized distance calculations
    init {
        try {
            System.loadLibrary("native-lib")
            Log.d("DistanceEstimator", "Native library loaded successfully")
        } catch (e: Exception) {
            Log.w("DistanceEstimator", "Failed to load native library: ${e.message}")
        }
    }
    
    // Native method declaration for optimized distance calculations
    private external fun calculateDistanceNative(
        ta: Int, rsrp: Int, rsrq: Int, sinr: Int
    ): FloatArray

    fun calculateEstimatedDistanceToTower(ta: Int?, rsrp: Int?, rsrq: Int?, sinr: Int?): Double? {
        android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: Starting distance calculation - TA: $ta, RSRP: $rsrp, RSRQ: $rsrq, SINR: $sinr")
        
        // First try native implementation if TA is available
        if (ta != null && ta > 0) {
            try {
                android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: Attempting native distance calculation with TA=$ta")
                val nativeResult = calculateDistanceNative(
                    ta,
                    rsrp ?: Int.MIN_VALUE,
                    rsrq ?: Int.MIN_VALUE,
                    sinr ?: Int.MIN_VALUE
                )
                if (nativeResult.isNotEmpty() && nativeResult[0] > 0) {
                    android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: Native calculation successful: ${nativeResult[0]} meters")
                    return nativeResult[0].toDouble()
                } else {
                    android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: Native calculation returned invalid result")
                }
            } catch (e: Exception) {
                android.util.Log.e("DistanceEstimator", "DIAGNOSTIC: Native distance calculation failed", e)
            }
        }
        
        // Treat TA=Integer.MAX_VALUE or invalid values as unknown
        if (ta == Int.MAX_VALUE || ta == null || ta < 0) {
            android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: TA unavailable or invalid (${ta ?: "null"}), using RSRP fallback")
        } else {
            android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: TA available: $ta, calculating distance")
        }

        var taDistance: Double? = null
        if (ta != null && ta >= 0) {
            taDistance = ta * METERS_PER_TA_UNIT
            android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: TA distance calculated: $taDistance meters (${String.format(java.util.Locale.US,"%.2f", taDistance/1000.0)} km)")
        }

        var rsrpDistance: Double? = null
        if (rsrp != null && rsrp != Int.MIN_VALUE) {
            android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: RSRP available: $rsrp dBm, calculating path loss distance")
            // Convert RSRP to path loss using enhanced environment-aware model
            val environment = determineEnvironment(null, null, rsrq, sinr)
            val (txPower, l0, pathLossExponent) = when (environment) {
                Environment.URBAN -> Triple(URBAN_TX_POWER_DBM, URBAN_L0_DB, URBAN_PATH_LOSS_EXPONENT_N)
                Environment.SUBURBAN -> Triple(SUBURBAN_TX_POWER_DBM, SUBURBAN_L0_DB, SUBURBAN_PATH_LOSS_EXPONENT_N)
                Environment.RURAL -> Triple(RURAL_TX_POWER_DBM, RURAL_L0_DB, RURAL_PATH_LOSS_EXPONENT_N)
            }
            
            val pathLoss = txPower - rsrp
            android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: Calculated path loss: $pathLoss dB (Environment: $environment, TxPower: $txPower - RSRP: $rsrp)")

            // Calculate distance from path loss model
            // Avoid log10(0) or negative values
            if (pathLoss > l0 && pathLossExponent > 0.0) {
                rsrpDistance = DEFAULT_D0_METERS * 10.0.pow((pathLoss - l0) / (10.0 * pathLossExponent))
                android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: RSRP distance calculated: ${String.format(java.util.Locale.US,"%.1f", rsrpDistance)} meters")
            } else {
                android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: Path loss calculation invalid (pathLoss: $pathLoss, L0: $l0, Exponent: $pathLossExponent)")
            }
        } else {
            android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: RSRP unavailable (${rsrp ?: "null"}), skipping RSRP distance calculation")
        }

        // Calculate quality weights based on RSRQ and SINR
        val rsrqWeight = if (rsrq != null) {
            val normalizedRsrq = ((rsrq + 3.0) / 17.0).coerceIn(0.0, 1.0)
            android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: RSRQ: $rsrq dB, normalized weight: ${String.format(java.util.Locale.US,"%.2f", normalizedRsrq)}")
            normalizedRsrq
        } else {
            android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: RSRQ unavailable, using default weight 1.0")
            1.0
        }

        val sinrWeight = if (sinr != null) {
            val normalizedSinr = ((sinr + 10.0) / 40.0).coerceIn(0.0, 1.0)
            android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: SINR: $sinr dB, normalized weight: ${String.format(java.util.Locale.US,"%.2f", normalizedSinr)}")
            normalizedSinr
        } else {
            android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: SINR unavailable, using default weight 1.0")
            1.0
        }

        // Combined quality weight (geometric mean)
        val qualityWeight = sqrt(rsrqWeight * sinrWeight)
        android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: Combined quality weight: ${String.format(java.util.Locale.US,"%.2f", qualityWeight)}")

        // Prioritize TA distance if available and reasonable
        if (taDistance != null && taDistance > 0) {
            val adjustedDistance = taDistance * (0.8 + 0.2 * qualityWeight)
            android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: Final TA-based distance: ${String.format(java.util.Locale.US,"%.1f", adjustedDistance)} meters (quality adjusted from $taDistance)")
            return adjustedDistance
        } else if (rsrpDistance != null && rsrpDistance > 0) {
            val adjustedDistance = rsrpDistance * (0.7 + 0.3 * qualityWeight)
            android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: Final RSRP-based distance: ${String.format(java.util.Locale.US,"%.1f", adjustedDistance)} meters (quality adjusted from ${String.format(java.util.Locale.US,"%.1f", rsrpDistance)})")
            return adjustedDistance
        }

        android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: No valid distance calculation possible, returning UNKNOWN_DISTANCE")
        // Return UNKNOWN_DISTANCE for towers without TA or RSRP
        return UNKNOWN_DISTANCE
    }

    /**
     * Calculates the great-circle distance between two points on the Earth's surface using the Haversine formula.
     * This is a utility method that delegates to CommonUtils for consistency.
     *
     * @param lat1 Latitude of the first point in degrees.
     * @param lon1 Longitude of the first point in degrees.
     * @param lat2 Latitude of the second point in degrees.
     * @param lon2 Longitude of the second point in degrees.
     * @return The distance between the two points in meters.
     */
    fun calculateHaversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        return CommonUtils.calculateDistance(lat1, lon1, lat2, lon2)
    }
    
    /**
     * Calculates distance using Timing Advance (TA) value only
     *
     * @param ta Timing Advance value (0-1282 for LTE)
     * @return Distance in meters, or null if TA is invalid
     */
    fun calculateDistanceFromTA(ta: Int?): Double? {
        if (ta == null || ta <= 0 || ta == Int.MAX_VALUE) {
            Log.d("DistanceEstimator", "TA unavailable or invalid: $ta")
            return null
        }
        
        val distance = ta * METERS_PER_TA_UNIT
        Log.d("DistanceEstimator", "TA distance: $distance meters (${String.format(java.util.Locale.US,"%.2f", distance/1000.0)} km)")
        return distance
    }
    
    /**
     * Calculates distance using RSRP-based path loss model
     *
     * @param rsrp Reference Signal Received Power in dBm
     * @param rsrq Reference Signal Received Quality in dB (optional)
     * @param sinr Signal to Interference plus Noise Ratio in dB (optional)
     * @param latitude Optional latitude for environment determination
     * @param longitude Optional longitude for environment determination
     * @return Distance in meters, or null if calculation fails
     */
    fun calculateDistanceFromRSRP(
        rsrp: Int,
        rsrq: Int? = null,
        sinr: Int? = null,
        latitude: Double? = null,
        longitude: Double? = null
    ): Double? {
        if (rsrp == Int.MIN_VALUE) {
            Log.d("DistanceEstimator", "RSRP unavailable: $rsrp")
            return null
        }

        // Determine environment based on location or signal characteristics
        val environment = determineEnvironment(latitude, longitude, rsrq, sinr)
        Log.d("DistanceEstimator", "Environment: $environment")

        // Get environment-specific constants
        val (txPower, l0, pathLossExponent) = when (environment) {
            Environment.URBAN -> Triple(URBAN_TX_POWER_DBM, URBAN_L0_DB, URBAN_PATH_LOSS_EXPONENT_N)
            Environment.SUBURBAN -> Triple(SUBURBAN_TX_POWER_DBM, SUBURBAN_L0_DB, SUBURBAN_PATH_LOSS_EXPONENT_N)
            Environment.RURAL -> Triple(RURAL_TX_POWER_DBM, RURAL_L0_DB, RURAL_PATH_LOSS_EXPONENT_N)
        }

        // Calculate path loss
        val pathLoss = txPower - rsrp
        Log.d("DistanceEstimator", "Path loss: $pathLoss dB (TxPower: $txPower - RSRP: $rsrp)")

        // Calculate distance using path loss model
        if (pathLoss > l0 && pathLossExponent > 0.0) {
            val distance = DEFAULT_D0_METERS * 10.0.pow((pathLoss - l0) / (10.0 * pathLossExponent))
            val qualityWeight = calculateQualityWeight(rsrq, sinr)
            val adjustedDistance = distance * qualityWeight
            
            Log.d("DistanceEstimator", "RSRP distance: ${String.format(java.util.Locale.US,"%.1f", adjustedDistance)} meters")
            return adjustedDistance
        }

        Log.d("DistanceEstimator", "Path loss calculation invalid")
        return null
    }

    /**
     * Enhanced RSRP-only distance estimation with environmental adaptation
     * Used when TA is unavailable or invalid
     */
    fun calculateEnhancedRsrpDistance(
        rsrp: Int,
        rsrq: Int?,
        sinr: Int?,
        latitude: Double?,
        longitude: Double?
    ): Double? {
        android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: Enhanced RSRP-only distance calculation")
        
        if (rsrp == Int.MIN_VALUE) {
            android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: RSRP unavailable, skipping enhanced calculation")
            return null
        }

        // Determine environment based on location or signal characteristics
        val environment = determineEnvironment(latitude, longitude, rsrq, sinr)
        android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: Determined environment: $environment")

        // Get environment-specific constants
        val (txPower, l0, pathLossExponent) = when (environment) {
            Environment.URBAN -> Triple(URBAN_TX_POWER_DBM, URBAN_L0_DB, URBAN_PATH_LOSS_EXPONENT_N)
            Environment.SUBURBAN -> Triple(SUBURBAN_TX_POWER_DBM, SUBURBAN_L0_DB, SUBURBAN_PATH_LOSS_EXPONENT_N)
            Environment.RURAL -> Triple(RURAL_TX_POWER_DBM, RURAL_L0_DB, RURAL_PATH_LOSS_EXPONENT_N)
        }

        // Calculate distance using environment-specific path loss model
        val pathLoss = txPower - rsrp
        android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: Calculated path loss: $pathLoss dB (TxPower: $txPower - RSRP: $rsrp)")

        if (pathLoss > l0 && pathLossExponent > 0.0) {
            val distance = DEFAULT_D0_METERS * 10.0.pow((pathLoss - l0) / (10.0 * pathLossExponent))
            android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: Raw RSRP distance: ${String.format("%.1f", distance)} meters")
            
            // Apply quality weighting based on RSRQ and SINR
            val qualityWeight = calculateQualityWeight(rsrq, sinr)
            val adjustedDistance = distance * qualityWeight
            
            android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: Quality-adjusted distance: ${String.format("%.1f", adjustedDistance)} meters (weight: ${String.format("%.2f", qualityWeight)})")
            return adjustedDistance
        }

        android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: Path loss calculation invalid")
        return null
    }

    /**
     * Analyze historical signal patterns for anomaly detection
     * This helps detect fake towers when TA is unavailable
     */
    fun analyzeHistoricalSignalPattern(
        currentRsrp: Int,
        historicalRsrpData: List<Int>,
        location: Pair<Double, Double>?
    ): SignalAnomalyResult? {
        if (historicalRsrpData.size < MIN_HISTORICAL_SAMPLES) {
            android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: Insufficient historical data (${historicalRsrpData.size} samples)")
            return null
        }

        val avgRsrp = historicalRsrpData.average()
        val stdDev = calculateStandardDeviation(historicalRsrpData, avgRsrp)
        
        android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: Historical analysis - Current: $currentRsrp, Average: ${String.format("%.1f", avgRsrp)}, StdDev: ${String.format("%.1f", stdDev)}")

        val deviation = currentRsrp - avgRsrp
        val zScore = if (stdDev > 0) deviation / stdDev else 0.0

        android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: Signal deviation: ${String.format("%.1f", deviation)} dB, Z-score: ${String.format("%.2f", zScore)}")

        return if (kotlin.math.abs(zScore) > SIGNAL_DEVIATION_THRESHOLD) {
            val anomalyType = if (zScore > 0) "STRONGER_THAN_EXPECTED" else "WEAKER_THAN_EXPECTED"
            android.util.Log.w("DistanceEstimator", "DIAGNOSTIC: SIGNAL ANOMALY DETECTED - $anomalyType (Z-score: ${String.format("%.2f", kotlin.math.abs(zScore))})")
            SignalAnomalyResult(anomalyType, kotlin.math.abs(zScore), deviation)
        } else {
            android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: Signal strength within normal range")
            null
        }
    }

    /**
     * Determine environment based on location context and signal characteristics
     */
    private fun determineEnvironment(
        latitude: Double?,
        longitude: Double?,
        rsrq: Int?,
        sinr: Int?
    ): Environment {
        // For now, use signal quality as proxy for environment
        // In a full implementation, this could use location-based databases
        return when {
            sinr != null && sinr > 15 && (rsrq ?: 0) > -10 -> Environment.RURAL // Good signal suggests rural
            sinr != null && sinr < 5 && (rsrq ?: 0) < -15 -> Environment.URBAN // Poor signal suggests urban
            else -> Environment.SUBURBAN // Default to suburban
        }
    }

    /**
     * Calculate quality weight based on RSRQ and SINR
     */
    private fun calculateQualityWeight(rsrq: Int?, sinr: Int?): Double {
        val rsrqWeight = if (rsrq != null) {
            // Normalize RSRQ: -20dB (worst) to -3dB (best)
            ((rsrq + 20.0) / 17.0).coerceIn(0.0, 1.0)
        } else 0.7 // Default weight if RSRQ unavailable

        val sinrWeight = if (sinr != null) {
            // Normalize SINR: -10dB (worst) to 30dB (best)
            ((sinr + 10.0) / 40.0).coerceIn(0.0, 1.0)
        } else 0.7 // Default weight if SINR unavailable

        // Combined weight with minimum threshold to avoid extreme values
        val combinedWeight = (rsrqWeight * 0.5 + sinrWeight * 0.5).coerceIn(0.5, 1.5)
        android.util.Log.d("DistanceEstimator", "DIAGNOSTIC: Quality weights - RSRQ: ${String.format("%.2f", rsrqWeight)}, SINR: ${String.format("%.2f", sinrWeight)}, Combined: ${String.format("%.2f", combinedWeight)}")
        
        return combinedWeight
    }

    /**
     * Calculates standard deviation of a list of integers
     *
     * @param data List of integer values
     * @param mean Pre-calculated mean of the data
     * @return Standard deviation
     */
    private fun calculateStandardDeviation(data: List<Int>, mean: Double): Double {
        val sum = data.sumOf { (it - mean).pow(2.0) }
        return kotlin.math.sqrt(sum / data.size)
    }

    /**
     * Environment types for signal propagation modeling
     */
    enum class Environment {
        URBAN, SUBURBAN, RURAL
    }

    /**
     * Result of signal anomaly analysis
     */
    data class SignalAnomalyResult(
        val anomalyType: String,
        val zScore: Double,
        val deviation: Double
    )
    
    /**
     * Configuration class for distance estimation parameters
     * Allows for runtime configuration of distance calculation algorithms
     */
    data class DistanceEstimationConfig(
        val enableNativeOptimization: Boolean = true,
        val environmentDetectionEnabled: Boolean = true,
        val qualityWeightingEnabled: Boolean = true,
        val historicalAnalysisEnabled: Boolean = true,
        val anomalyDetectionThreshold: Double = SIGNAL_DEVIATION_THRESHOLD
    )
}
