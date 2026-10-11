package com.cymatune.util

import android.util.Log
import com.cymatune.db.TowerDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs

object TowerFingerprinter {

    private const val NUM_CLOSEST_FINGERPRINTS = 5 // Number of closest fingerprints to average for location estimation

    /**
     * Estimates the precise location of a cell tower using a fingerprinting approach.
     * It queries the database for past observations of the same tower and finds the closest matches
     * based on signal characteristics (RSRP, TA).
     *
     * @param currentObservation The most recent TowerObservation for the tower to be located.
     * @param towerDao The DAO for accessing TowerObservation data.
     * @return A Pair of (latitude, longitude) for the estimated tower location, or null if estimation fails.
     */
    suspend fun estimateTowerLocationByFingerprint(
        currentObservation: TowerObservation,
        towerDao: TowerDao
    ): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        val towerObservations = towerDao.getObservationsForTower(
            currentObservation.cid,
            currentObservation.lac,
            currentObservation.mcc,
            currentObservation.mnc
        )

        // Filter out the current observation itself and any observations without location data
        val validFingerprints = towerObservations.filter {
            it.id != currentObservation.id && it.latitude != null && it.longitude != null && it.rsrp != Int.MIN_VALUE
        }

        if (validFingerprints.isEmpty()) {
            Log.d("TowerFingerprinter", "No valid fingerprints found for tower ${currentObservation.cid}")
            return@withContext null
        }

        // Calculate similarity score for each fingerprint
        val scoredFingerprints = validFingerprints.map { fingerprint ->
            val rsrpDiff = abs(currentObservation.rsrp - fingerprint.rsrp)
            val taDiff = if (currentObservation.ta != null && fingerprint.ta != null) {
                abs(currentObservation.ta - fingerprint.ta)
            } else {
                0 // If TA is not available for either, assume no difference for this metric
            }
            
            // Calculate differences for RSRQ and SINR if available
            val rsrqDiff = if (currentObservation.rsrq != null && fingerprint.rsrq != null) {
                abs(currentObservation.rsrq - fingerprint.rsrq)
            } else {
                0 // If RSRQ is not available for either, assume no difference for this metric
            }
            
            val sinrDiff = if (currentObservation.sinr != null && fingerprint.sinr != null) {
                abs(currentObservation.sinr - fingerprint.sinr)
            } else {
                0 // If SINR is not available for either, assume no difference for this metric
            }

            // A simple similarity score: lower is better (more similar)
            // You might want to weight RSRP, TA, RSRQ, and SINR differently based on their reliability
            val similarityScore = rsrpDiff + taDiff + rsrqDiff + sinrDiff

            Pair(fingerprint, similarityScore)
        }.sortedBy { it.second } // Sort by similarity score (lowest first)

        // Take the top N closest fingerprints
        val topNFingerprints = scoredFingerprints.take(NUM_CLOSEST_FINGERPRINTS)

        if (topNFingerprints.isEmpty()) {
            Log.d("TowerFingerprinter", "Not enough top N fingerprints after scoring.")
            return@withContext null
        }

        // Average the locations of the top N fingerprints
        var sumLat = 0.0
        var sumLon = 0.0
        var count = 0

        for ((fingerprint, _) in topNFingerprints) {
            fingerprint.latitude?.let { lat ->
                fingerprint.longitude?.let { lon ->
                    sumLat += lat
                    sumLon += lon
                    count++
                }
            }
        }

        if (count > 0) {
            val estimatedLat = sumLat / count
            val estimatedLon = sumLon / count
            Log.d("TowerFingerprinter", "Estimated tower location by fingerprint: Lat=$estimatedLat, Lon=$estimatedLon")
            return@withContext Pair(estimatedLat, estimatedLon)
        } else {
            Log.e("TowerFingerprinter", "Failed to average locations from top N fingerprints.")
            return@withContext null
        }
    }
}
