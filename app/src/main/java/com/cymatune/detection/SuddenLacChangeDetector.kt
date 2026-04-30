package com.cymatune.detection

import android.util.Log
import com.cymatune.db.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

/**
 * Sudden LAC Change Detector
 * 
 * Specialized detection for rapid LAC changes in small geographic areas,
 * which is a common characteristic of fake towers used for IMSI catchers.
 * 
 * Detection Strategy:
 * 1. Track LAC changes within small geographic radius
 * 2. Analyze timing patterns of LAC transitions
 * 3. Detect unusual LAC density in small areas
 * 4. Identify suspicious LAC transition patterns
 */
/**
 * SuddenLACChangeDetector with Movement-Aware thresholds (B09-B12 fix)
 * Adjusts detection sensitivity based on user movement state.
 */
class SuddenLacChangeDetector(
    private val lacCidPatternDao: LacCidPatternDao,
    private val locationHistoryDao: LocationHistoryDao
) {

    companion object {
        private const val TAG = "SuddenLacChangeDetector"

        // Configuration thresholds
        private const val SMALL_AREA_RADIUS_METERS = 500.0
        private const val MIN_LAC_DENSITY_THRESHOLD = 5
        private const val MAX_TIME_BETWEEN_CHANGES_MS = 300000 // 5 minutes
        private const val MIN_SUSPICIOUS_CHANGES = 3
        private const val MAX_NORMAL_LAC_CHANGES_PER_HOUR = 2
        private const val LAC_CHANGE_ANOMALY_SCORE = 80.0
        private const val DENSITY_ANOMALY_SCORE = 60.0
        private const val PATTERN_ANOMALY_SCORE = 70.0

        // B09-B12: Movement-aware multipliers
        fun getMovementMultiplier(intensity: MovementDetector.MovementIntensity): Double {
            return when (intensity) {
                MovementDetector.MovementIntensity.STATIONARY -> 1.0
                MovementDetector.MovementIntensity.WALKING -> 1.2
                MovementDetector.MovementIntensity.MOVING -> 1.5
                MovementDetector.MovementIntensity.TRAVELING -> 2.0
            }
        }
    }

    // Current movement intensity for threshold adjustment
    private var currentMovementIntensity: MovementDetector.MovementIntensity = MovementDetector.MovementIntensity.STATIONARY

    /**
     * Set current movement intensity for threshold adjustment
     */
    fun setMovementIntensity(intensity: MovementDetector.MovementIntensity) {
        currentMovementIntensity = intensity
    }

    /**
     * Detect sudden LAC changes in small geographic areas
     */
    suspend fun detectSuddenLacChanges(
        currentLocation: Pair<Double, Double>,
        currentTime: Long = System.currentTimeMillis()
    ): SuddenLacChangeResult {
        return withContext(Dispatchers.IO) {
            try {
                val suspiciousEvents = mutableListOf<SuspiciousLacEvent>()
                val detectionReasons = mutableListOf<String>()
                
                // 1. Check for high LAC density in small area
                val lacDensityResult = detectHighLacDensity(currentLocation)
                if (lacDensityResult.isSuspicious) {
                    lacDensityResult.event?.let { suspiciousEvents.add(it) }
                    detectionReasons.add(lacDensityResult.reason)
                }
                
                // 2. Check for rapid LAC changes in recent history
                val rapidChangeResult = detectRapidLacChanges(currentLocation, currentTime)
                if (rapidChangeResult.isSuspicious) {
                    suspiciousEvents.addAll(rapidChangeResult.events)
                    detectionReasons.addAll(rapidChangeResult.reasons)
                }
                
                // 3. Check for suspicious LAC transition patterns
                val patternResult = detectSuspiciousLacPatterns(currentLocation, currentTime)
                if (patternResult.isSuspicious) {
                    suspiciousEvents.addAll(patternResult.events)
                    detectionReasons.addAll(patternResult.reasons)
                }
                
                val isSuspicious = suspiciousEvents.isNotEmpty()
                val maxAnomalyScore = if (isSuspicious) {
                    calculateMaxAnomalyScore(suspiciousEvents)
                } else 0.0
                
                SuddenLacChangeResult(
                    isSuspicious = isSuspicious,
                    anomalyScore = maxAnomalyScore,
                    suspiciousEvents = suspiciousEvents,
                    detectionReasons = detectionReasons,
                    suggestedActions = generateSuggestedActions(isSuspicious, maxAnomalyScore)
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error detecting sudden LAC changes", e)
                SuddenLacChangeResult(
                    isSuspicious = false,
                    anomalyScore = 0.0,
                    suspiciousEvents = emptyList(),
                    detectionReasons = listOf("Detection failed: ${e.message}"),
                    suggestedActions = listOf("Retry detection later")
                )
            }
        }
    }

    /**
     * Detect high LAC density in small geographic area
     */
    private suspend fun detectHighLacDensity(
        location: Pair<Double, Double>
    ): LacDensityResult {
        val nearbyPatterns = lacCidPatternDao.getPatternsInRadius(
            location.first, location.second, SMALL_AREA_RADIUS_METERS
        )
        
        if (nearbyPatterns.isEmpty()) {
            return LacDensityResult(false, null, "No nearby patterns found")
        }
        
        // Group by LAC to count unique LACs in the area
        val lacGroups = nearbyPatterns.groupBy { it.lac }
        val uniqueLacs = lacGroups.keys.toSet()
        
        if (uniqueLacs.size >= MIN_LAC_DENSITY_THRESHOLD) {
            val avgAnomalyScore = nearbyPatterns.map { it.anomalyScore }.average()
            val suspiciousLacs = lacGroups.filter { it.value.any { pattern -> pattern.anomalyScore > 50.0 } }
            
            val event = SuspiciousLacEvent(
                eventType = LacEventType.HIGH_LAC_DENSITY,
                location = location,
                timestamp = System.currentTimeMillis(),
                affectedLacs = uniqueLacs.toList(),
                affectedCids = nearbyPatterns.map { it.cid }.distinct(),
                anomalyScore = DENSITY_ANOMALY_SCORE + (uniqueLacs.size - MIN_LAC_DENSITY_THRESHOLD) * 5.0,
                confidence = calculateDensityConfidence(uniqueLacs.size, suspiciousLacs.size, avgAnomalyScore)
            )
            
            return LacDensityResult(
                true,
                event,
                "High LAC density detected: ${uniqueLacs.size} LACs in ${SMALL_AREA_RADIUS_METERS}m radius (avg anomaly: ${String.format(java.util.Locale.US,"%.1f", avgAnomalyScore)})"
            )
        }
        
        return LacDensityResult(false, null, "LAC density within normal range: ${uniqueLacs.size} LACs")
    }

    /**
     * Detect rapid LAC changes in recent history
     */
    private suspend fun detectRapidLacChanges(
        location: Pair<Double, Double>,
        currentTime: Long
    ): RapidLacChangeResult {
        // Use getFrequentLocations which returns a regular list
        val frequentLocations = locationHistoryDao.getFrequentLocations(0) // Get all with 0+ minutes
        val recentHistory = frequentLocations.filter { history ->
            history.lastSeen >= currentTime - (60 * 60 * 1000) && history.lastSeen <= currentTime
        }
        
        if (recentHistory.size < 2) {
            return RapidLacChangeResult(false, emptyList(), emptyList())
        }
        
        val nearbyHistory = recentHistory.filter { history ->
            val distance = calculateDistance(
                location.first, location.second,
                history.latitude, history.longitude
            )
            distance <= (SMALL_AREA_RADIUS_METERS / 1000) // Convert to km
        }
        
        if (nearbyHistory.size < MIN_SUSPICIOUS_CHANGES) {
            return RapidLacChangeResult(false, emptyList(), emptyList())
        }
        
        // Analyze LAC changes over time
        // B09-B12: Apply movement-aware threshold multiplier
        val movementMultiplier = getMovementMultiplier(currentMovementIntensity)
        val adjustedSuspiciousChangesThreshold = (MIN_SUSPICIOUS_CHANGES * movementMultiplier).toInt().coerceAtLeast(3)

        val lacChanges = mutableListOf<LacChange>()
        var previousEntry: LocationHistory? = null

        for (entry in nearbyHistory.sortedBy { it.lastSeen }) {
            if (previousEntry != null && previousEntry.lac != entry.lac) {
                val timeDiff = entry.lastSeen - previousEntry.lastSeen

                // B09-B12: Adjust time window based on movement
                val adjustedMaxTime = (MAX_TIME_BETWEEN_CHANGES_MS * movementMultiplier).toLong()
                if (timeDiff <= adjustedMaxTime) {
                    lacChanges.add(
                        LacChange(
                            fromLac = previousEntry.lac,
                            toLac = entry.lac,
                            timestamp = entry.lastSeen,
                            timeBetweenChanges = timeDiff,
                            location = Pair(entry.latitude, entry.longitude)
                        )
                    )
                }
            }
            previousEntry = entry
        }

        // B09-B12: Apply movement multiplier to suspicious changes threshold
        val suspiciousChanges = lacChanges.filter { change ->
            change.timeBetweenChanges < (MAX_TIME_BETWEEN_CHANGES_MS / 10 * movementMultiplier).toLong() // Very rapid changes
        }

        if (suspiciousChanges.size >= adjustedSuspiciousChangesThreshold) {
            val events = suspiciousChanges.map { change ->
                SuspiciousLacEvent(
                    eventType = LacEventType.RAPID_LAC_CHANGE,
                    location = change.location,
                    timestamp = change.timestamp,
                    affectedLacs = listOf(change.fromLac, change.toLac),
                    affectedCids = emptyList(),
                    anomalyScore = LAC_CHANGE_ANOMALY_SCORE + (MAX_TIME_BETWEEN_CHANGES_MS / change.timeBetweenChanges),
                    confidence = calculateRapidChangeConfidence(change.timeBetweenChanges)
                )
            }
            
            val reasons = listOf(
                "Rapid LAC changes detected: ${suspiciousChanges.size} changes in ${SMALL_AREA_RADIUS_METERS}m radius",
                "Fastest change: ${String.format(java.util.Locale.US,"%.1f", suspiciousChanges.minOfOrNull { it.timeBetweenChanges }?.toDouble()?.div(1000))} seconds"
            )
            
            return RapidLacChangeResult(true, events, reasons)
        }
        
        return RapidLacChangeResult(false, emptyList(), emptyList())
    }

    /**
     * Detect suspicious LAC transition patterns
     */
    private suspend fun detectSuspiciousLacPatterns(
        location: Pair<Double, Double>,
        currentTime: Long
    ): SuspiciousLacPatternResult {
        val patterns = lacCidPatternDao.getPatternsInRadius(
            location.first, location.second, SMALL_AREA_RADIUS_METERS * 2
        )
        
        if (patterns.size < 3) {
            return SuspiciousLacPatternResult(false, emptyList(), emptyList())
        }
        
        val suspiciousPatterns = mutableListOf<SuspiciousLacEvent>()
        val detectionReasons = mutableListOf<String>()
        
        // Check for LACs with very short dwell times
        // Fix: Reduced from 5 minutes to 30 seconds to avoid false positives while driving
        val shortDwellPatterns = patterns.filter { it.lastSeen - it.firstSeen < 30000 } // Less than 30 seconds
        
        if (shortDwellPatterns.size >= MIN_SUSPICIOUS_CHANGES) {
            val event = SuspiciousLacEvent(
                eventType = LacEventType.SHORT_DWELL_TIME,
                location = location,
                timestamp = currentTime,
                affectedLacs = shortDwellPatterns.map { it.lac },
                affectedCids = shortDwellPatterns.map { it.cid },
                anomalyScore = PATTERN_ANOMALY_SCORE,
                confidence = calculatePatternConfidence(shortDwellPatterns.size)
            )
            
            suspiciousPatterns.add(event)
            detectionReasons.add("Multiple LACs with short dwell times: ${shortDwellPatterns.size} towers")
        }
        
        // Check for unusual LAC clustering patterns
        val lacGroups = patterns.groupBy { it.lac }
        val suspiciousClusters = lacGroups.filter { it.value.size == 1 } // LACs with only one tower
        
        if (suspiciousClusters.size >= patterns.size * 0.7) { // 70% or more are singleton LACs
            val singletonEvent = SuspiciousLacEvent(
                eventType = LacEventType.UNUSUAL_LAC_CLUSTERING,
                location = location,
                timestamp = currentTime,
                affectedLacs = suspiciousClusters.keys.toList(),
                affectedCids = suspiciousClusters.values.flatten().map { it.cid },
                anomalyScore = PATTERN_ANOMALY_SCORE + 20.0,
                confidence = calculateClusteringConfidence(suspiciousClusters.size.toDouble() / patterns.size)
            )
            
            suspiciousPatterns.add(singletonEvent)
            detectionReasons.add("Unusual LAC clustering: ${String.format(java.util.Locale.US,"%.1f", suspiciousClusters.size.toDouble() / patterns.size * 100)}% singleton LACs")
        }
        
        return SuspiciousLacPatternResult(
            suspiciousPatterns.isNotEmpty(),
            suspiciousPatterns,
            detectionReasons
        )
    }

    /**
     * Calculate maximum anomaly score from multiple events
     */
    private fun calculateMaxAnomalyScore(events: List<SuspiciousLacEvent>): Double {
        return events.maxOfOrNull { it.anomalyScore } ?: 0.0
    }

    /**
     * Calculate confidence for density detection
     */
    private fun calculateDensityConfidence(
        totalLacs: Int,
        suspiciousLacs: Int,
        avgAnomalyScore: Double
    ): Double {
        val densityFactor = min(totalLacs.toDouble() / MIN_LAC_DENSITY_THRESHOLD, 2.0)
        val suspiciousnessFactor = suspiciousLacs.toDouble() / totalLacs
        val anomalyFactor = avgAnomalyScore / 100.0
        
        return (densityFactor * 0.4 + suspiciousnessFactor * 0.3 + anomalyFactor * 0.3) * 100.0
    }

    /**
     * Calculate confidence for rapid change detection
     */
    private fun calculateRapidChangeConfidence(timeBetweenChanges: Long): Double {
        val normalizedTime = min(timeBetweenChanges.toDouble() / MAX_TIME_BETWEEN_CHANGES_MS, 1.0)
        return (1.0 - normalizedTime) * 100.0
    }

    /**
     * Calculate confidence for pattern detection
     */
    private fun calculatePatternConfidence(patternCount: Int): Double {
        return min(patternCount * 20.0, 100.0)
    }

    /**
     * Calculate confidence for clustering detection
     */
    private fun calculateClusteringConfidence(singletonRatio: Double): Double {
        return singletonRatio * 100.0
    }

    /**
     * Generate suggested actions based on detection results
     */
    private fun generateSuggestedActions(isSuspicious: Boolean, anomalyScore: Double): List<String> {
        if (!isSuspicious) {
            return listOf("Continue monitoring")
        }
        
        return when {
            anomalyScore > 80.0 -> {
                listOf(
                    "HIGH RISK: Immediate investigation recommended",
                    "Consider blocking connections in this area",
                    "Log all tower interactions for forensic analysis",
                    "Alert security team"
                )
            }
            anomalyScore > 60.0 -> {
                listOf(
                    "MEDIUM RISK: Enhanced monitoring required",
                    "Collect additional signal measurements",
                    "Monitor for repeated suspicious patterns",
                    "Consider user notification"
                )
            }
            anomalyScore > 40.0 -> {
                listOf(
                    "LOW RISK: Continue observation",
                    "Increase detection sensitivity in this area",
                    "Log additional data points for analysis"
                )
            }
            else -> {
                listOf("Monitor for pattern changes")
            }
        }
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

    // Data classes for results
    private data class LacDensityResult(
        val isSuspicious: Boolean,
        val event: SuspiciousLacEvent?,
        val reason: String
    )
    
    private data class RapidLacChangeResult(
        val isSuspicious: Boolean,
        val events: List<SuspiciousLacEvent>,
        val reasons: List<String>
    )
    
    private data class SuspiciousLacPatternResult(
        val isSuspicious: Boolean,
        val events: List<SuspiciousLacEvent>,
        val reasons: List<String>
    )
    
    private data class LacChange(
        val fromLac: Int,
        val toLac: Int,
        val timestamp: Long,
        val timeBetweenChanges: Long,
        val location: Pair<Double, Double>
    )
}

/**
 * Result of sudden LAC change detection
 */
data class SuddenLacChangeResult(
    val isSuspicious: Boolean,
    val anomalyScore: Double,
    val suspiciousEvents: List<SuspiciousLacEvent>,
    val detectionReasons: List<String>,
    val suggestedActions: List<String>
)

/**
 * Types of LAC events that can be suspicious
 */
enum class LacEventType {
    HIGH_LAC_DENSITY,           // Too many LACs in small area
    RAPID_LAC_CHANGE,           // Rapid LAC changes
    SHORT_DWELL_TIME,           // Towers with very short active periods
    UNUSUAL_LAC_CLUSTERING      // Unusual clustering patterns
}

/**
 * Suspicious LAC event detected
 */
data class SuspiciousLacEvent(
    val eventType: LacEventType,
    val location: Pair<Double, Double>,
    val timestamp: Long,
    val affectedLacs: List<Int>,
    val affectedCids: List<Int>,
    val anomalyScore: Double,
    val confidence: Double = 100.0
)