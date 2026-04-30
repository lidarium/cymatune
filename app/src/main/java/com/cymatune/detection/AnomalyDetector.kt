package com.cymatune.detection

import android.util.Log
import com.cymatune.db.SignalBaseline
import com.cymatune.db.SignalBaselineDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Statistical Anomaly Detector using Z-Score methodology.
 * Detects when a tower's signal behavior deviates significantly from its learned baseline.
 */
class AnomalyDetector(
    private val signalBaselineDao: SignalBaselineDao
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    
    companion object {
        const val Z_SCORE_THRESHOLD = 2.5 // 2.5 standard deviations = ~98.8% confidence
        const val MIN_SAMPLES_FOR_DETECTION = 10 // Need at least 10 samples for reliable baseline
    }
    
    data class AnomalyResult(
        val isAnomaly: Boolean,
        val anomalyScore: Double, // 0.0 - 1.0
        val reasons: List<String>
    )
    
    /**
     * Detect if current signal parameters are anomalous compared to baseline
     */
    suspend fun detectAnomaly(
        cid: Int,
        lac: Int,
        mcc: Int,
        mnc: Int,
        currentRsrp: Int,
        currentRsrq: Int?,
        currentSinr: Int?,
        currentNeighborCount: Int
    ): AnomalyResult {
        val baseline = signalBaselineDao.getBaseline(cid, lac, mcc, mnc)
        
        // No baseline = no anomaly (yet)
        if (baseline == null || baseline.sampleCount < MIN_SAMPLES_FOR_DETECTION) {
            return AnomalyResult(false, 0.0, emptyList())
        }
        
        val reasons = mutableListOf<String>()
        var maxZScore = 0.0
        
        // Check RSRP deviation
        val rsrpZScore = calculateZScore(currentRsrp.toDouble(), baseline.avgRsrp, baseline.stdDevRsrp)
        if (abs(rsrpZScore) > Z_SCORE_THRESHOLD) {
            reasons.add("Signal strength anomaly (Z-score: %.2f)".format(rsrpZScore))
            maxZScore = maxOf(maxZScore, abs(rsrpZScore))
        }
        
        // Check neighbor count deviation
        val neighborZScore = calculateZScore(
            currentNeighborCount.toDouble(),
            baseline.avgNeighborCount.toDouble(),
            baseline.stdDevNeighborCount
        )
        if (abs(neighborZScore) > Z_SCORE_THRESHOLD) {
            reasons.add("Neighbor count anomaly (Z-score: %.2f)".format(neighborZScore))
            maxZScore = maxOf(maxZScore, abs(neighborZScore))
        }
        
        // Calculate anomaly score (0.0 - 1.0)
        val anomalyScore = (maxZScore / 5.0).coerceIn(0.0, 1.0) // Normalize to 0-1

        return AnomalyResult(
            isAnomaly = reasons.isNotEmpty(),
            anomalyScore = anomalyScore,
            reasons = reasons
        )
    }
    
    /**
     * Update or create baseline with new observation
     */
    fun recordObservation(
        cid: Int,
        lac: Int,
        mcc: Int,
        mnc: Int,
        latitude: Double,
        longitude: Double,
        rsrp: Int,
        rsrq: Int?,
        sinr: Int?,
        neighborCount: Int
    ) {
        scope.launch {
            try {
                val existing = signalBaselineDao.getBaseline(cid, lac, mcc, mnc)
                
                if (existing != null) {
                    // Update running statistics
                    val newCount = existing.sampleCount + 1
                    val newAvgRsrp = updateRunningAverage(existing.avgRsrp, rsrp.toDouble(), existing.sampleCount)
                    val newStdDevRsrp = updateRunningStdDev(
                        existing.avgRsrp, existing.stdDevRsrp,
                        rsrp.toDouble(), newAvgRsrp, existing.sampleCount
                    )
                    
                    val newAvgNeighborCount = updateRunningAverage(
                        existing.avgNeighborCount.toDouble(),
                        neighborCount.toDouble(),
                        existing.sampleCount
                    ).toInt()
                    val newStdDevNeighborCount = updateRunningStdDev(
                        existing.avgNeighborCount.toDouble(),
                        existing.stdDevNeighborCount,
                        neighborCount.toDouble(),
                        newAvgNeighborCount.toDouble(),
                        existing.sampleCount
                    )
                    
                    val updated = existing.copy(
                        avgRsrp = newAvgRsrp,
                        avgRsrq = rsrq?.toDouble() ?: existing.avgRsrq,
                        avgSinr = sinr?.toDouble() ?: existing.avgSinr,
                        avgNeighborCount = newAvgNeighborCount,
                        stdDevRsrp = newStdDevRsrp,
                        stdDevNeighborCount = newStdDevNeighborCount,
                        sampleCount = newCount,
                        lastSeen = System.currentTimeMillis()
                    )
                    signalBaselineDao.updateBaseline(updated)
                } else {
                    // Create new baseline
                    val newBaseline = SignalBaseline(
                        cid = cid,
                        lac = lac,
                        mcc = mcc,
                        mnc = mnc,
                        latitude = latitude,
                        longitude = longitude,
                        avgRsrp = rsrp.toDouble(),
                        avgRsrq = rsrq?.toDouble(),
                        avgSinr = sinr?.toDouble(),
                        avgNeighborCount = neighborCount,
                        stdDevRsrp = 0.0, // Will be computed after more samples
                        stdDevNeighborCount = 0.0,
                        sampleCount = 1,
                        firstSeen = System.currentTimeMillis(),
                        lastSeen = System.currentTimeMillis()
                    )
                    signalBaselineDao.insertBaseline(newBaseline)
                }
            } catch (e: Exception) {
                Log.e("AnomalyDetector", "Failed to record observation", e)
            }
        }
    }
    
    /**
     * Calculate Z-Score: (value - mean) / stdDev
     */
    private fun calculateZScore(value: Double, mean: Double, stdDev: Double): Double {
        return if (stdDev > 0) {
            (value - mean) / stdDev
        } else {
            0.0 // No variance = no anomaly
        }
    }
    
    /**
     * Update running average: new_avg = (old_avg * n + new_value) / (n + 1)
     */
    private fun updateRunningAverage(oldAvg: Double, newValue: Double, n: Int): Double {
        return (oldAvg * n + newValue) / (n + 1)
    }
    
    /**
     * Update running standard deviation using Welford's algorithm
     */
    private fun updateRunningStdDev(
        oldMean: Double,
        oldStdDev: Double,
        newValue: Double,
        newMean: Double,
        n: Int
    ): Double {
        if (n == 0) return 0.0
        
        val oldVariance = oldStdDev * oldStdDev
        val m2Old = oldVariance * n
        val m2New = m2Old + (newValue - oldMean) * (newValue - newMean)
        val newVariance = m2New / (n + 1)
        
        return sqrt(newVariance)
    }
}
