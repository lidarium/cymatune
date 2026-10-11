package com.cymatune.detection

import android.util.Log
import com.cymatune.db.*
import com.cymatune.util.TowerInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

// Import the specific types from LacCidPattern
import com.cymatune.db.PatternType
import com.cymatune.db.AnomalyType
import com.cymatune.db.LacCidAnomalyResult

/**
 * LAC/CID Pattern Analyzer
 *
 * Implements outlier detection for LAC/CID patterns to identify potential fake towers.
 * Analyzes geographic consistency, statistical patterns, and temporal anomalies.
 *
 * Detection Strategies:
 * 1. Geographic Outlier Detection - towers in unexpected locations
 * 2. Statistical Pattern Analysis - unusual CID/LAC distributions
 * 3. Temporal Anomaly Detection - towers appearing at unusual times
 * 4. Cluster Inconsistency Detection - towers not fitting expected patterns
 */
class LacCidPatternAnalyzer(
    private val lacCidPatternDao: LacCidPatternDao,
    private val locationHistoryDao: LocationHistoryDao,
    private val towerDao: TowerDao
) {

    companion object {
        private const val TAG = "LacCidPatternAnalyzer"
        
        // Configuration thresholds
        private const val DEFAULT_ANOMALY_THRESHOLD = 70.0
        private const val DEFAULT_CONFIDENCE_THRESHOLD = 30.0
        private const val GEOGRAPHIC_RADIUS_METERS = 1000.0
        private const val MAX_UNUSUAL_DISTANCE_KM = 50.0
        private const val MIN_PATTERN_COUNT = 3
        private const val SUSPICIOUS_CLUSTER_SIZE = 5
    }

    /**
     * Analyze a tower observation for LAC/CID pattern anomalies
     */
    suspend fun analyzeTowerForLacCidAnomalies(
        towerInfo: TowerInfo,
        location: Pair<Double, Double>?,
        signalStrength: Int? = null
    ): LacCidAnomalyResult {
        return withContext(Dispatchers.IO) {
            try {
                val anomalyTypes = mutableListOf<AnomalyType>()
                val detectionReasons = mutableListOf<String>()
                val suggestedActions = mutableListOf<String>()
                
                var anomalyScore = 0.0
                var confidenceScore = 100.0
                
                // 1. Check for geographic outliers
                val geographicResult = analyzeGeographicConsistency(towerInfo, location)
                if (geographicResult.isAnomalous) {
                    anomalyTypes.add(AnomalyType.UNUSUAL_GEOGRAPHIC_LOCATION)
                    anomalyScore += geographicResult.anomalyScore
                    detectionReasons.add(geographicResult.reason)
                    confidenceScore -= geographicResult.anomalyScore * 0.5
                }
                
                // 2. Check for statistical outliers
                val statisticalResult = analyzeStatisticalPattern(towerInfo, location)
                if (statisticalResult.isAnomalous) {
                    anomalyTypes.add(AnomalyType.OUTLIER_CID)
                    anomalyScore += statisticalResult.anomalyScore
                    detectionReasons.add(statisticalResult.reason)
                    confidenceScore -= statisticalResult.anomalyScore * 0.3
                }
                
                // 3. Check for cluster inconsistencies
                val clusterResult = analyzeClusterConsistency(towerInfo, location)
                if (clusterResult.isAnomalous) {
                    anomalyTypes.add(AnomalyType.CLUSTER_INCONSISTENCY)
                    anomalyScore += clusterResult.anomalyScore
                    detectionReasons.add(clusterResult.reason)
                    confidenceScore -= clusterResult.anomalyScore * 0.4
                }
                
                // 4. Check for temporal anomalies (if signal strength available)
                if (signalStrength != null) {
                    val temporalResult = analyzeTemporalPattern(towerInfo, signalStrength)
                    if (temporalResult.isAnomalous) {
                        anomalyTypes.add(AnomalyType.UNUSUAL_SIGNAL_PATTERN)
                        anomalyScore += temporalResult.anomalyScore
                        detectionReasons.add(temporalResult.reason)
                        confidenceScore -= temporalResult.anomalyScore * 0.2
                    }
                }
                
                // Cap scores
                anomalyScore = anomalyScore.coerceIn(0.0, 100.0)
                confidenceScore = confidenceScore.coerceIn(0.0, 100.0)
                
                // Determine suggested actions
                when {
                    anomalyScore > 80.0 -> {
                        suggestedActions.add("HIGH RISK: Flag tower for immediate investigation")
                        suggestedActions.add("Consider blocking connection to this tower")
                    }
                    anomalyScore > 60.0 -> {
                        suggestedActions.add("MEDIUM RISK: Monitor this tower for repeated anomalies")
                        suggestedActions.add("Log additional observations for pattern analysis")
                    }
                    anomalyScore > 30.0 -> {
                        suggestedActions.add("LOW RISK: Continue monitoring")
                        suggestedActions.add("Collect more data points")
                    }
                }
                
                LacCidAnomalyResult(
                    isAnomalous = anomalyScore > DEFAULT_ANOMALY_THRESHOLD,
                    anomalyScore = anomalyScore,
                    confidenceScore = confidenceScore,
                    anomalyTypes = anomalyTypes,
                    detectionReasons = detectionReasons,
                    suggestedActions = suggestedActions
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error analyzing LAC/CID pattern for tower ${towerInfo.cid}", e)
                LacCidAnomalyResult(
                    isAnomalous = false,
                    anomalyScore = 0.0,
                    confidenceScore = 0.0,
                    anomalyTypes = emptyList(),
                    detectionReasons = listOf("Analysis failed: ${e.message}"),
                    suggestedActions = listOf("Retry analysis later")
                )
            }
        }
    }

    /**
     * Analyze geographic consistency of a tower
     */
    private suspend fun analyzeGeographicConsistency(
        towerInfo: TowerInfo,
        location: Pair<Double, Double>?
    ): PatternAnalysisResult {
        if (location == null) {
            return PatternAnalysisResult(false, 0.0, "No location data available")
        }
        
        // Get existing patterns for this tower
        val existingPatterns = lacCidPatternDao.getPatternsForTower(
            towerInfo.mcc, towerInfo.mnc, towerInfo.lac, towerInfo.cid
        )
        
        if (existingPatterns.isEmpty()) {
            // First observation - create initial pattern
            createInitialPattern(towerInfo, location)
            return PatternAnalysisResult(false, 0.0, "New tower pattern established")
        }
        
        val latestPattern = existingPatterns.first()
        
        // Fix: Ignore 0.0 coordinates which indicate invalid/uninitialized location
        if (location.first == 0.0 && location.second == 0.0) {
            return PatternAnalysisResult(false, 0.0, "Current location invalid, skipping analysis")
        }

        // Fix: If stored pattern has invalid coordinates, update it instead of flagging anomaly
        if (latestPattern.latitude == 0.0 && latestPattern.longitude == 0.0) {
            updatePatternWithObservation(latestPattern, location)
            return PatternAnalysisResult(false, 0.0, "Updated invalid initial pattern location")
        }

        val distanceFromCenter = calculateDistance(
            location.first, location.second,
            latestPattern.latitude, latestPattern.longitude
        )
        
        // Check if this observation is far from the expected location
        if (distanceFromCenter > latestPattern.locationRadius) {
            val anomalyScore = calculateGeographicAnomalyScore(distanceFromCenter, latestPattern.locationRadius)
            
            return PatternAnalysisResult(
                true,
                anomalyScore,
                "Tower observed ${String.format(java.util.Locale.US,"%.1f", distanceFromCenter)}km from expected location (radius: ${latestPattern.locationRadius}m)"
            )
        }
        
        // Update existing pattern with new observation
        updatePatternWithObservation(latestPattern, location)
        
        return PatternAnalysisResult(false, 0.0, "Tower location consistent with historical pattern")
    }

    /**
     * Analyze statistical patterns for outlier detection
     */
    private suspend fun analyzeStatisticalPattern(
        towerInfo: TowerInfo,
        location: Pair<Double, Double>?
    ): PatternAnalysisResult {
        if (location == null) {
            return PatternAnalysisResult(false, 0.0, "No location data available")
        }
        
        // Get all patterns in the geographic area
        val nearbyPatterns = lacCidPatternDao.getPatternsInRadius(
            location.first, location.second, GEOGRAPHIC_RADIUS_METERS
        )
        
        if (nearbyPatterns.isEmpty()) {
            return PatternAnalysisResult(false, 0.0, "No nearby patterns for comparison")
        }
        
        // Analyze LAC distribution
        val lacCounts = nearbyPatterns.groupBy { it.lac }.mapValues { it.value.size }
        val currentLacCount = lacCounts[towerInfo.lac] ?: 0
        val maxLacCount = lacCounts.values.maxOrNull() ?: 1
        
        // Check if this LAC has unusually few towers
        if (currentLacCount < maxLacCount * 0.1 && maxLacCount > 5) {
            val anomalyScore = 40.0 + (maxLacCount - currentLacCount).toDouble() * 2.0
            return PatternAnalysisResult(
                true,
                anomalyScore.coerceIn(0.0, 100.0),
                "LAC ${towerInfo.lac} has unusually few towers (${currentLacCount}) compared to nearby LACs (max: $maxLacCount)"
            )
        }
        
        // Analyze CID distribution within this LAC
        val lacPatterns = nearbyPatterns.filter { it.lac == towerInfo.lac }
        val cidCounts = lacPatterns.groupBy { it.cid }.mapValues { it.value.size }
        val currentCidCount = cidCounts[towerInfo.cid] ?: 0
        val maxCidCount = cidCounts.values.maxOrNull() ?: 1
        
        // Check if this CID is rarely seen in this LAC
        if (currentCidCount < MIN_PATTERN_COUNT && maxCidCount > 3) {
            val anomalyScore = 30.0 + (MIN_PATTERN_COUNT - currentCidCount) * 10.0
            return PatternAnalysisResult(
                true,
                anomalyScore.coerceIn(0.0, 100.0),
                "CID ${towerInfo.cid} rarely observed in LAC ${towerInfo.lac} (count: $currentCidCount)"
            )
        }
        
        return PatternAnalysisResult(false, 0.0, "CID/LAC distribution appears normal")
    }

    /**
     * Analyze cluster consistency
     */
    private suspend fun analyzeClusterConsistency(
        towerInfo: TowerInfo,
        location: Pair<Double, Double>?
    ): PatternAnalysisResult {
        if (location == null) {
            return PatternAnalysisResult(false, 0.0, "No location data available")
        }
        
        // Get patterns in a wider radius for cluster analysis
        val clusterPatterns = lacCidPatternDao.getPatternsInRadius(
            location.first, location.second, GEOGRAPHIC_RADIUS_METERS * 2
        )
        
        if (clusterPatterns.size < SUSPICIOUS_CLUSTER_SIZE) {
            return PatternAnalysisResult(false, 0.0, "Insufficient towers for cluster analysis")
        }
        
        // Group by LAC to identify suspicious patterns
        val lacGroups = clusterPatterns.groupBy { it.lac }
        
        // Check for LACs with suspicious tower distributions
        for ((lac, lacTowers) in lacGroups) {
            if (lacTowers.size >= SUSPICIOUS_CLUSTER_SIZE) {
                val avgAnomalyScore = lacTowers.map { it.anomalyScore }.average()
                
                // Check if this LAC has unusually high anomaly scores
                if (avgAnomalyScore > 50.0) {
                    val towerAnomalyScore = lacTowers.find { it.cid == towerInfo.cid }?.anomalyScore ?: 0.0
                    
                    return PatternAnalysisResult(
                        true,
                        towerAnomalyScore + 20.0,
                        "Tower in LAC $lac with high average anomaly score (${String.format(java.util.Locale.US,"%.1f", avgAnomalyScore)})"
                    )
                }
            }
        }
        
        return PatternAnalysisResult(false, 0.0, "Tower cluster appears normal")
    }

    /**
     * Analyze temporal patterns based on signal strength
     */
    private suspend fun analyzeTemporalPattern(
        towerInfo: TowerInfo,
        signalStrength: Int
    ): PatternAnalysisResult {
        // Get recent patterns for this tower
        val recentPatterns = lacCidPatternDao.getRecentPatterns(
            System.currentTimeMillis() - (24 * 60 * 60 * 1000) // Last 24 hours
        ).filter { 
            it.mcc == towerInfo.mcc && it.mnc == towerInfo.mnc &&
            it.lac == towerInfo.lac && it.cid == towerInfo.cid
        }
        
        if (recentPatterns.size < MIN_PATTERN_COUNT) {
            return PatternAnalysisResult(false, 0.0, "Insufficient temporal data")
        }
        
        // Analyze signal strength patterns
        val avgSignal = recentPatterns.mapNotNull { it.confidenceScore }.average()
        val signalVariance = recentPatterns.mapNotNull { it.confidenceScore }.let { signals ->
            if (signals.size > 1) {
                val mean = signals.average()
                signals.map { (it - mean) * (it - mean) }.average()
            } else 0.0
        }
        
        // Check for unusual signal patterns (very high or very low)
        if (abs(signalStrength) > abs(avgSignal) + (sqrt(signalVariance) * 2)) {
            val anomalyScore = 25.0 + abs(signalStrength - avgSignal) * 0.5
            return PatternAnalysisResult(
                true,
                anomalyScore.coerceIn(0.0, 100.0),
                "Unusual signal strength: $signalStrength dBm (avg: ${String.format(java.util.Locale.US,"%.1f", avgSignal)} dBm)"
            )
        }
        
        return PatternAnalysisResult(false, 0.0, "Signal pattern appears normal")
    }

    /**
     * Create initial pattern for a new tower
     */
    private suspend fun createInitialPattern(
        towerInfo: TowerInfo,
        location: Pair<Double, Double>
    ) {
        val pattern = LacCidPattern(
            cid = towerInfo.cid,
            lac = towerInfo.lac,
            mcc = towerInfo.mcc,
            mnc = towerInfo.mnc,
            latitude = location.first,
            longitude = location.second,
            patternType = PatternType.GEOGRAPHIC,
            firstSeen = System.currentTimeMillis(),
            lastSeen = System.currentTimeMillis()
        )
        
        lacCidPatternDao.insertPattern(pattern)
        Log.d(TAG, "Created initial pattern for tower ${towerInfo.cid} in LAC ${towerInfo.lac}")
    }

    /**
     * Update existing pattern with new observation
     */
    private suspend fun updatePatternWithObservation(
        pattern: LacCidPattern,
        location: Pair<Double, Double>
    ) {
        val distance = calculateDistance(
            location.first, location.second,
            pattern.latitude, pattern.longitude
        )
        
        lacCidPatternDao.updatePatternStatistics(
            pattern.id,
            System.currentTimeMillis(),
            distance
        )
    }

    /**
     * Calculate distance between two geographic points using Haversine formula
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        
        val a = sin(dLat / 2) * sin(dLat / 2) +
                sin(dLon / 2) * sin(dLon / 2) * cos(lat1Rad) * cos(lat2Rad)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return earthRadiusKm * c
    }

    /**
     * Calculate geographic anomaly score based on distance from expected location
     */
    private fun calculateGeographicAnomalyScore(distance: Double, expectedRadius: Double): Double {
        val distanceKm = distance * 1000 // Convert to meters
        return when {
            distanceKm < expectedRadius -> 0.0
            distanceKm < expectedRadius * 2 -> 30.0
            distanceKm < expectedRadius * 5 -> 60.0
            distanceKm < expectedRadius * 10 -> 80.0
            else -> 95.0
        }
    }

    /**
     * Result of pattern analysis
     */
    private data class PatternAnalysisResult(
        val isAnomalous: Boolean,
        val anomalyScore: Double,
        val reason: String
    )
}