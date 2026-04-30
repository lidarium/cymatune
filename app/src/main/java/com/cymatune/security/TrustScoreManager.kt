package com.cymatune.security

import android.content.Context
import android.util.Log
import com.cymatune.db.LocationHistory
import com.cymatune.db.LocationHistoryDao
import com.cymatune.detection.ConnectionStateMonitor
import com.cymatune.detection.AnomalyDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

/**
 * Trust Score Engine
 * Calculates a 0-100 trust score for the current cellular connection.
 * Replaces binary "Fake/Real" detection with contextual analysis.
 */
class TrustScoreManager(
    private val context: Context,
    private val locationHistoryDao: LocationHistoryDao,
    private val anomalyDetector: AnomalyDetector? = null
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    
    companion object {
        const val NEUTRAL_SCORE = 50
        const val MAX_SCORE = 100
        const val MIN_SCORE = 0
        
        // Trust bonuses
        const val BONUS_FREQUENT_LOCATION = 20
        const val BONUS_KNOWN_LOCATION = 10
        const val BONUS_TIME_CONNECTED = 10 // per hour, capped at 30
        
        // Trust penalties
        const val PENALTY_CONNECTION_STALL = 30
        const val PENALTY_FAILURE_LOOP = 40
        const val PENALTY_SILENT_DROP = 20
        const val PENALTY_NEIGHBOR_INCONSISTENCY = 40
        const val PENALTY_GEOGRAPHIC_ANOMALY = 50
        const val PENALTY_PROTOCOL_ANOMALY = 30
        const val PENALTY_STATISTICAL_ANOMALY = 35
    }
    
    data class TrustScore(
        val score: Int,
        val factors: List<String>
    )
    
    private var currentScore = NEUTRAL_SCORE
    private var connectionStartTime: Long? = null
    private val scoringFactors = mutableListOf<String>()
    
    /**
     * Calculate trust score for a given tower
     */
    suspend fun calculateTrustScore(
        cid: Int,
        lac: Int,
        mcc: Int,
        mnc: Int,
        latitude: Double?,
        longitude: Double?,
        rsrp: Int? = null,
        rsrq: Int? = null,
        sinr: Int? = null,
        neighborCount: Int? = null,
        anomalies: List<ConnectionStateMonitor.ConnectionAnomaly> = emptyList(),
        detectionReasons: List<String> = emptyList(),
        isUserMoving: Boolean = false,  // NEW: User movement state from accelerometer
        movementIntensity: Int = 0      // NEW: Movement intensity (0-15 bonus points)
    ): TrustScore {
        var score = NEUTRAL_SCORE
        val factors = mutableListOf<String>()
        
        // NEW: Bonus for user movement (reduces new tower penalties)
        if (isUserMoving && movementIntensity > 0) {
            score += movementIntensity
            val movementType = when (movementIntensity) {
                5 -> "Walking"
                10 -> "Moving"
                15 -> "Traveling"
                else -> "Moving"
            }
            factors.add("+$movementIntensity: User $movementType (new towers expected)")
        }
        
        // Bonus: Check if this is a known location
        val locationHistory = locationHistoryDao.getLocationHistory(cid, lac, mcc, mnc)
        if (locationHistory != null) {
            when {
                locationHistory.totalDurationMinutes > 1440 -> { // >24 hours
                    score += BONUS_FREQUENT_LOCATION
                    factors.add("+$BONUS_FREQUENT_LOCATION: Frequent location (${locationHistory.label ?: "Unlabeled"})")
                }
                locationHistory.totalDurationMinutes > 60 -> { // >1 hour
                    score += BONUS_KNOWN_LOCATION
                    factors.add("+$BONUS_KNOWN_LOCATION: Known location")
                }
            }
        }
        
        // Bonus: Time connected (if we're tracking this connection)
        connectionStartTime?.let { startTime ->
            val hoursConnected = (System.currentTimeMillis() - startTime) / (1000 * 60 * 60)
            val timeBonus = min(hoursConnected.toInt() * BONUS_TIME_CONNECTED, 30)
            if (timeBonus > 0) {
                score += timeBonus
                factors.add("+$timeBonus: Time connected (${hoursConnected}h)")
            }
        }
        
        // Penalties: Connection anomalies
        for (anomaly in anomalies) {
            when (anomaly.type) {
                ConnectionStateMonitor.AnomalyType.STALL -> {
                    score -= PENALTY_CONNECTION_STALL
                    factors.add("-$PENALTY_CONNECTION_STALL: Connection stalling")
                }
                ConnectionStateMonitor.AnomalyType.FAILURE_LOOP -> {
                    score -= PENALTY_FAILURE_LOOP
                    factors.add("-$PENALTY_FAILURE_LOOP: Repeated failures")
                }
                ConnectionStateMonitor.AnomalyType.SILENT_DROP -> {
                    score -= PENALTY_SILENT_DROP
                    factors.add("-$PENALTY_SILENT_DROP: Unexpected disconnection")
                }
            }
        }
        
        // Penalties: Detection reasons
        for (reason in detectionReasons) {
            when {
                reason.contains("Geographic anomaly", ignoreCase = true) -> {
                    score -= PENALTY_GEOGRAPHIC_ANOMALY
                    factors.add("-$PENALTY_GEOGRAPHIC_ANOMALY: Geographic inconsistency")
                }
                reason.contains("Protocol anomaly", ignoreCase = true) -> {
                    score -= PENALTY_PROTOCOL_ANOMALY
                    factors.add("-$PENALTY_PROTOCOL_ANOMALY: Protocol violation")
                }
                reason.contains("Neighbor", ignoreCase = true) -> {
                    score -= PENALTY_NEIGHBOR_INCONSISTENCY
                    factors.add("-$PENALTY_NEIGHBOR_INCONSISTENCY: Neighbor inconsistency")
                }
            }
        }
        
        // Check for statistical anomalies (if detector available and we have signal data)
        if (anomalyDetector != null && rsrp != null && neighborCount != null) {
            try {
                val anomalyResult = anomalyDetector.detectAnomaly(
                    cid, lac, mcc, mnc,
                    rsrp, rsrq, sinr, neighborCount
                )
                
                if (anomalyResult.isAnomaly) {
                    val penalty = (anomalyResult.anomalyScore * PENALTY_STATISTICAL_ANOMALY).toInt()
                    score -= penalty
                    factors.add("-$penalty: Statistical anomaly - ${anomalyResult.reasons.joinToString(", ")}")
                }
            } catch (e: Exception) {
                Log.e("TrustScoreManager", "Anomaly detection failed", e)
            }
        }
        
        // Clamp score
        score = score.coerceIn(MIN_SCORE, MAX_SCORE)
        
        return TrustScore(score, factors)
    }
    
    /**
     * Update location history for a tower
     */
    fun recordConnection(
        cid: Int,
        lac: Int,
        mcc: Int,
        mnc: Int,
        latitude: Double,
        longitude: Double,
        durationMinutes: Long = 0
    ) {
        scope.launch {
            try {
                val existing = locationHistoryDao.getLocationHistory(cid, lac, mcc, mnc)
                if (existing != null) {
                    // Update existing record
                    val updated = existing.copy(
                        lastSeen = System.currentTimeMillis(),
                        totalConnections = existing.totalConnections + 1,
                        totalDurationMinutes = existing.totalDurationMinutes + durationMinutes
                    )
                    locationHistoryDao.updateLocationHistory(updated)
                } else {
                    // Create new record
                    val newRecord = LocationHistory(
                        cid = cid,
                        lac = lac,
                        mcc = mcc,
                        mnc = mnc,
                        latitude = latitude,
                        longitude = longitude,
                        firstSeen = System.currentTimeMillis(),
                        lastSeen = System.currentTimeMillis(),
                        totalConnections = 1,
                        totalDurationMinutes = durationMinutes
                    )
                    locationHistoryDao.insertLocationHistory(newRecord)
                }
            } catch (e: Exception) {
                Log.e("TrustScoreManager", "Failed to record connection", e)
            }
        }
    }
    
    /**
     * Start tracking a connection
     */
    fun startTracking() {
        connectionStartTime = System.currentTimeMillis()
    }
    
    /**
     * Stop tracking a connection and return duration
     */
    fun stopTracking(): Long {
        return connectionStartTime?.let { start ->
            val duration = (System.currentTimeMillis() - start) / (1000 * 60) // minutes
            connectionStartTime = null
            duration
        } ?: 0
    }
}
