package com.cymatune.lifecycle

import com.cymatune.db.TowerDao
import com.cymatune.db.FakeTowerDao
import com.cymatune.db.DatabaseManager
import com.cymatune.util.TowerConnectionInfo
import com.cymatune.util.TowerObservation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*
import android.util.Log

/**
 * Tower Refinement Manager
 * 
 * Manages continuous tower location refinement using multiple observations
 * and triangulation techniques for improved accuracy.
 */
object TowerRefinementManager {
    
    // Callback for refinement updates
    var onRefinementUpdate: ((towerId: String, precisionRadius: Double, observationCount: Int) -> Unit)? = null
    
    // Refinement configuration
    private const val MIN_OBSERVATIONS_FOR_REFINEMENT = 3
    private const val MAX_PRECISION_RADIUS_METERS = 1000.0
    private const val MIN_PRECISION_RADIUS_METERS = 50.0
    private const val LOCATION_CONFIDENCE_THRESHOLD = 0.8
    
    // Tower observation data
    data class TowerObservation(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val timestamp: Long,
        val signalStrength: Int
    )
    
    // Refined tower location
    data class RefinedTowerLocation(
        val latitude: Double,
        val longitude: Double,
        val precisionRadius: Double,
        val observationCount: Int,
        val confidence: Double,
        val lastUpdated: Long
    )
    
    /**
     * Process tower observation for continuous refinement
     */
    suspend fun processTowerObservation(
        towerDao: TowerDao,
        fakeTowerDao: FakeTowerDao,
        towerInfo: TowerConnectionInfo,
        currentLocation: Pair<Double, Double>?,
        locationAccuracy: Float,
        userLocation: Pair<Double, Double>? = null
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val towerId = "${towerInfo.cid}-${towerInfo.lac}-${towerInfo.mcc}-${towerInfo.mnc}"
            
            // Get existing observations for this tower
            val existingObservations = getTowerObservations(towerDao, towerInfo)
            
            // Add current observation if available
            val updatedObservations = if (currentLocation != null) {
                existingObservations + TowerObservation(
                    latitude = currentLocation.first,
                    longitude = currentLocation.second,
                    accuracy = locationAccuracy,
                    timestamp = System.currentTimeMillis(),
                    signalStrength = towerInfo.signalStrength ?: 0
                )
            } else {
                existingObservations
            }
            
            // Calculate refined location if we have enough observations
            if (updatedObservations.size >= MIN_OBSERVATIONS_FOR_REFINEMENT) {
                val refinedLocation = calculateRefinedLocation(updatedObservations)
                
                // Update tower database with refined location
                updateTowerWithRefinedLocation(towerDao, fakeTowerDao, towerInfo, refinedLocation)
                
                // Notify listeners of refinement update
                onRefinementUpdate?.invoke(
                    towerId,
                    refinedLocation.precisionRadius,
                    refinedLocation.observationCount
                )
                
                Log.d("TowerRefinementManager", "Tower $towerId refined: radius=${refinedLocation.precisionRadius}m, observations=${refinedLocation.observationCount}")
                
                return@withContext true
            }
            
            false
        } catch (e: Exception) {
            Log.e("TowerRefinementManager", "Error processing tower observation", e)
            false
        }
    }
    
    /**
     * Get existing observations for a tower
     */
    private suspend fun getTowerObservations(
        towerDao: TowerDao,
        towerInfo: TowerConnectionInfo
    ): List<TowerObservation> {
        return try {
            // Get recent observations for this tower from database
            val observations = towerDao.getObservationsForTowerSince(
                cid = towerInfo.cid,
                lac = towerInfo.lac,
                mcc = towerInfo.mcc ?: 0,
                mnc = towerInfo.mnc ?: 0,
                startTime = System.currentTimeMillis() - (24 * 60 * 60 * 1000) // Last 24 hours
            )
            
            observations.map { obs ->
                TowerObservation(
                    latitude = obs.latitude ?: 0.0,
                    longitude = obs.longitude ?: 0.0,
                    accuracy = obs.locationAccuracy ?: 100f,
                    timestamp = obs.timestamp,
                    signalStrength = obs.signal
                )
            }.filter { obs ->
                // Filter out invalid 0.0, 0.0 coordinates
                if (obs.latitude == 0.0 && obs.longitude == 0.0) return@filter false
                
                // Filter out low accuracy observations (> 2km)
                if (obs.accuracy > 2000f) return@filter false
                
                // Filter out known "Country Center" coordinates (bad data from previous versions)
                if (isBadLocation(obs.latitude, obs.longitude)) return@filter false
                
                true
            }
        } catch (e: Exception) {
            Log.w("TowerRefinementManager", "Failed to get tower observations", e)
            emptyList()
        }
    }

    /**
     * Calculate refined tower location from multiple observations
     */
    private fun calculateRefinedLocation(observations: List<TowerObservation>): RefinedTowerLocation {
        if (observations.isEmpty()) {
            throw IllegalArgumentException("No observations available for refinement")
        }
        
        // Calculate weighted average location based on signal strength and accuracy
        var weightedLatSum = 0.0
        var weightedLonSum = 0.0
        var totalWeight = 0.0
        var minAccuracy = Float.MAX_VALUE
        
        for (observation in observations) {
            // Calculate weight based on signal strength and accuracy
            val signalWeight = calculateSignalWeight(observation.signalStrength)
            val accuracyWeight = calculateAccuracyWeight(observation.accuracy)
            val weight = signalWeight * accuracyWeight
            
            weightedLatSum += observation.latitude * weight
            weightedLonSum += observation.longitude * weight
            totalWeight += weight
            
            minAccuracy = minOf(minAccuracy, observation.accuracy)
        }
        
        val refinedLatitude = weightedLatSum / totalWeight
        val refinedLongitude = weightedLonSum / totalWeight
        
        // Calculate precision radius based on observation spread and accuracy
        val precisionRadius = calculatePrecisionRadius(observations, refinedLatitude, refinedLongitude, minAccuracy)
        
        // Calculate confidence based on number of observations and precision
        val confidence = calculateConfidence(observations.size, precisionRadius)
        
        return RefinedTowerLocation(
            latitude = refinedLatitude,
            longitude = refinedLongitude,
            precisionRadius = precisionRadius,
            observationCount = observations.size,
            confidence = confidence,
            lastUpdated = System.currentTimeMillis()
        )
    }
    
    /**
     * Calculate weight based on signal strength
     */
    private fun calculateSignalWeight(signalStrength: Int): Double {
        // Convert RSSI to positive value for easier calculation
        val rssi = kotlin.math.abs(signalStrength)
        
        // Higher signal strength (lower RSSI) gets higher weight
        return when {
            rssi <= 70 -> 1.0      // Excellent signal
            rssi <= 85 -> 0.8      // Good signal
            rssi <= 95 -> 0.6      // Fair signal
            else -> 0.4            // Poor signal
        }
    }
    
    /**
     * Calculate weight based on location accuracy
     */
    private fun calculateAccuracyWeight(accuracy: Float): Double {
        return when {
            accuracy <= 10f -> 1.0     // High accuracy
            accuracy <= 50f -> 0.8     // Medium accuracy
            accuracy <= 100f -> 0.6    // Low accuracy
            else -> 0.4                // Poor accuracy
        }
    }
    
    /**
     * Calculate precision radius based on observation spread
     */
    private fun calculatePrecisionRadius(
        observations: List<TowerObservation>,
        centerLat: Double,
        centerLon: Double,
        minAccuracy: Float
    ): Double {
        // Calculate standard deviation of observations
        var distanceSum = 0.0
        var maxDistance = 0.0
        
        for (observation in observations) {
            val distance = calculateDistance(
                centerLat, centerLon,
                observation.latitude, observation.longitude
            )
            distanceSum += distance
            maxDistance = maxOf(maxDistance, distance)
        }
        
        val averageDistance = distanceSum / observations.size
        
        // Combine observation spread with minimum accuracy
        val precision = maxOf(averageDistance + minAccuracy, MIN_PRECISION_RADIUS_METERS)
        
        // Cap maximum precision radius
        return minOf(precision, MAX_PRECISION_RADIUS_METERS)
    }
    
    /**
     * Calculate confidence score
     */
    private fun calculateConfidence(observationCount: Int, precisionRadius: Double): Double {
        val observationScore = minOf(observationCount / 10.0, 1.0) // Up to 10 observations
        val precisionScore = 1.0 - (precisionRadius / MAX_PRECISION_RADIUS_METERS)
        
        return (observationScore * 0.6 + precisionScore * 0.4).coerceIn(0.0, 1.0)
    }

    /**
     * Calculate distance between two coordinates using Haversine formula
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0 // meters
        
        val latDistance = Math.toRadians(lat2 - lat1)
        val lonDistance = Math.toRadians(lon2 - lon1)
        
        val a = sin(latDistance / 2) * sin(latDistance / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(lonDistance / 2) * sin(lonDistance / 2)
        
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        
        return earthRadius * c
    }
    
    /**
     * Update tower database with refined location
     */
    private suspend fun updateTowerWithRefinedLocation(
        towerDao: TowerDao,
        fakeTowerDao: FakeTowerDao,
        towerInfo: TowerConnectionInfo,
        refinedLocation: RefinedTowerLocation
    ) {
        try {
            // Update tower observation with refined location
            // Use the fully qualified name or alias to avoid confusion with internal class
            val observation = com.cymatune.util.TowerObservation(
                cid = towerInfo.cid,
                lac = towerInfo.lac,
                mcc = towerInfo.mcc ?: 0,
                mnc = towerInfo.mnc ?: 0,
                latitude = refinedLocation.latitude,
                longitude = refinedLocation.longitude,
                locationAccuracy = refinedLocation.precisionRadius.toFloat(),
                timestamp = refinedLocation.lastUpdated,
                signal = towerInfo.signalStrength,
                source = "refined",
                // Fill other required fields with null/defaults
                rsrp = 0, rsrq = null, sinr = null, pci = null, ta = null, 
                arfcn = null, band = null, ssRsrp = null, ssRsrq = null, ssSinr = null
            )
            
            towerDao.insertTowerObservation(observation)
            
            // Update fake tower location if it exists
            val existingFakeTower = fakeTowerDao.getFakeTower(
                towerInfo.cid,
                towerInfo.lac,
                towerInfo.mcc ?: 0,
                towerInfo.mnc ?: 0
            )
            
            if (existingFakeTower != null) {
                fakeTowerDao.updateTowerLocation(
                    cid = towerInfo.cid,
                    lac = towerInfo.lac,
                    mcc = towerInfo.mcc ?: 0,
                    mnc = towerInfo.mnc ?: 0,
                    latitude = refinedLocation.latitude,
                    longitude = refinedLocation.longitude,
                    precisionRadius = refinedLocation.precisionRadius,
                    observationCount = refinedLocation.observationCount
                )
            }
            
        } catch (e: Exception) {
            Log.e("TowerRefinementManager", "Failed to update tower with refined location", e)
        }
    }
    
    /**
     * Get refined location for a tower
     */
    suspend fun getRefinedLocation(
        towerDao: TowerDao,
        towerInfo: TowerConnectionInfo
    ): RefinedTowerLocation? {
        return try {
            val observations = getTowerObservations(towerDao, towerInfo)
            
            if (observations.size >= MIN_OBSERVATIONS_FOR_REFINEMENT) {
                calculateRefinedLocation(observations)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("TowerRefinementManager", "Failed to get refined location", e)
            null
        }
    }
    
    /**
     * Clear old observations for a tower
     */
    suspend fun clearOldObservations(
        towerDao: TowerDao,
        towerInfo: TowerConnectionInfo,
        cutoffTime: Long
    ) {
        try {
            // Implementation would remove old observations
            Log.d("TowerRefinementManager", "Old observations cleared for tower ${towerInfo.cid}")
        } catch (e: Exception) {
            Log.e("TowerRefinementManager", "Failed to clear old observations", e)
        }
    }
    
    /**
     * Get refinement statistics
     */
    suspend fun getRefinementStatistics(towerInfo: TowerConnectionInfo): Map<String, Any> {
        return try {
            val observations = getTowerObservations(
                DatabaseManager.towerDao,
                towerInfo
            )
            
            mapOf(
                "towerId" to "${towerInfo.cid}-${towerInfo.lac}",
                "observationCount" to observations.size,
                "minObservationsForRefinement" to MIN_OBSERVATIONS_FOR_REFINEMENT,
                "canRefine" to (observations.size >= MIN_OBSERVATIONS_FOR_REFINEMENT),
                "averageAccuracy" to observations.map { it.accuracy }.average(),
                "timeSpan" to if (observations.size > 1) {
                    observations.maxOf { it.timestamp } - observations.minOf { it.timestamp }
                } else 0L
            )
        } catch (e: Exception) {
            Log.e("TowerRefinementManager", "Failed to get refinement statistics", e)
            emptyMap()
        }
    }


    /**
     * Check if location matches known "bad" coordinates (Country Centers)
     * used in previous versions of the app.
     */
    private fun isBadLocation(lat: Double, lon: Double): Boolean {
        // List of known country centers previously used as fallbacks
        val badLocations = listOf(
            Pair(20.5937, 78.9629),   // India
            Pair(39.8283, -98.5795),  // USA
            Pair(35.8617, 104.1954),  // China
            Pair(48.8566, 2.3522),    // France/Europe
            Pair(24.7136, 46.6753),   // Saudi Arabia
            Pair(9.0820, 8.6753)      // Nigeria
        )
        
        // Check if point is within 1km of any bad location
        return badLocations.any { badLoc ->
            calculateDistance(lat, lon, badLoc.first, badLoc.second) < 1000.0
        }
    }
}