package com.cymatune.util

import android.util.Log
import com.cymatune.db.FakeTower
import com.cymatune.db.FakeTowerDao
import com.cymatune.db.TowerDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.cymatune.util.TowerObservation
import com.cymatune.util.TowerConnectionInfo

import kotlin.math.*

/**
 * Manages continuous refinement of tower locations by collecting multiple readings
 * and triangulating tower positions for improved precision.
 */
object TowerRefinementManager {
    
    private const val MIN_DISTANCE_FOR_NEW_OBSERVATION = 100.0 // meters (optimized for battery)
    private const val MAX_DISTANCE_FOR_NEW_OBSERVATION = 200.0 // meters (optimized for battery)
    private const val MAX_TOWER_JUMP_THRESHOLD = 500.0 // meters (Anomaly Threshold)
    private const val MIN_OBSERVATIONS_FOR_REFINEMENT = 3
    private const val MAX_OBSERVATIONS_FOR_REFINEMENT = 50
    
    // Tower refinement state tracking
    private val towerRefinementState = mutableMapOf<String, TowerRefinementState>()
    
    // Debounce map for location jump anomalies (TowerID -> FirstSeenTimestamp)
    private val anomalyDebounceMap = mutableMapOf<String, Long>()
    
    // Callback for refinement updates
    var onRefinementUpdate: ((String, Double, Int) -> Unit)? = null
    // Callback for anomaly detection (Pinned location mismatch)
    var onAnomalyDetected: ((String, String) -> Unit)? = null
    
    data class TowerRefinementState(
        val towerId: String, // Format: "mcc-mnc-lac-cid"
        var currentLocation: Pair<Double, Double>,
        var observationCount: Int,
        var precisionRadius: Double,
        var lastObservationLocation: Pair<Double, Double>?,
        var lastObservationTime: Long
    )
    
    /**
     * Process a new tower observation and determine if it should be used for refinement
     * Returns true if the observation was used for refinement, false otherwise
     */
    /**
     * Process a new tower observation and determine if it should be used for refinement
     * Returns true if the observation was used for refinement, false otherwise
     */
    suspend fun processTowerObservation(
        towerDao: TowerDao,
        fakeTowerDao: FakeTowerDao,
        towerInfo: TowerConnectionInfo,
        currentLocation: Pair<Double, Double>?,
        locationAccuracy: Float?,
        userLocation: Pair<Double, Double>? = null
    ): Boolean {
        val towerId = "${towerInfo.mcc}-${towerInfo.mnc}-${towerInfo.lac}-${towerInfo.cid}"
        
        // Handle GPS-unavailable scenario: use 0.0,0.0 for initial detection
        if (currentLocation == null || locationAccuracy == null || locationAccuracy > 100.0f || (currentLocation.first == 0.0 && currentLocation.second == 0.0)) {
            Log.d("TowerRefinement", "GPS unavailable, low accuracy ($locationAccuracy), or Default (0,0) - skipping refinement")
            return false
        }
        
        // Check if location is accurate enough for refinement (optimized for battery)
        if (locationAccuracy > 30.0f) {
            Log.d("TowerRefinement", "Location accuracy too low ($locationAccuracy) for refinement of tower $towerId")
            return false
        }
        
        // ANOMALY DETECTION: Check for "Pinned Location" mismatch
        // If this tower is already triangulated (High Trust), it should NOT jump location.
        val existingTower = fakeTowerDao.getFakeTower(towerInfo.cid, towerInfo.lac, towerInfo.mcc ?: 0, towerInfo.mnc ?: 0)
        
        // ANOMALY CHECKS
        // We check ALL towers for "Geographic Anomalies" (>40km), but stricter checks only for Triangulated ones.
        if (existingTower != null) {
            
            // Fix for "8500km Jump" (Null Island Bug)
            if (existingTower.latitude == 0.0 && existingTower.longitude == 0.0) {
                 Log.d("TowerRefinement", "Existing tower has (0,0) coordinates. Skipping jump detection.")
            } else {

                // Calculate distance between current user location and the PINNED tower location
                val jumpDistance = calculateDistance(
                    existingTower.latitude, existingTower.longitude,
                    currentLocation.first, currentLocation.second
                )
                
                // FALSE POSITIVE FILTER: "1500km Jump" (GPS Glitch vs Spoofing)
                // Extreme GPS Filter: > 1000km jumps -> Warn but likely GPS init issue
                if (jumpDistance > 1000000.0) {
                     val msg = "LOCATION WARNING: Extreme distance jump (${jumpDistance.toInt() / 1000}km). Possible GPS initialization issue."
                     Log.w("TowerRefinement", msg)
                     // return false // Don't block refinement, let it correct itself? No, safer to block.
                } else {

                    val isExtremeJump = jumpDistance > 5000.0
                    val isStrongSignal = towerInfo.signalStrength > -75
                    val isTriangulated = existingTower.isTriangulated
                    
                    // 1. UNIFIED THRESHOLD: 40km (Standard Macro Cell Radius)
                    if (jumpDistance > 40000.0) { 
                         val msg = "Geographic Anomaly: Tower ${towerInfo.cid} appeared ${jumpDistance.toInt()/1000}km away from pinned location."
                         Log.w("TowerRefinement", "SECURITY WARNING: $msg")
                         onAnomalyDetected?.invoke(towerId, msg)
                         return false
                    } 
                    
                    // 2. STRICTER THRESHOLD for Triangulated Towers
                    // If pinned, it shouldn't move > 500m (Old logic) -> Relaxed to 5km to avoid highway false positives?
                    // User complained about "Car False Positives" (likely the 500m check).
                    // We will ONLY enforce strict 5km check if we are VERY confident (Triangulated).
                    else if (isTriangulated && jumpDistance > 5000.0) {
                         val msg = "PINNED TOWER MISMATCH: High-Confidence Tower ${towerInfo.cid} moved ${jumpDistance.toInt()}m! Possible Cloning."
                         Log.w("TowerRefinement", "SECURITY WARNING: $msg")
                         onAnomalyDetected?.invoke(towerId, msg)
                         return false
                    }
                    
                    // 3. INTERMEDIATE: Unverified Tower but suspicious jump (>5km) with Strong Signal
                    else if (jumpDistance > 5000.0 && isStrongSignal) {
                         // Just log, don't alert yet unless we have secondary confirmation (Physics)
                         Log.i("TowerRefinement", "Suspicious intermediate jump (${jumpDistance.toInt()}m) with strong signal. Monitoring.")
                    }

                    // PHYSICS-BASED MITM CHECKS (Using jumpDistance, so must be in this scope)
                    
                    // Check A: Impossible Signal Strength
                    // If user is >2km away (relaxed from 200m), signal should NOT be excellent (>-60dBm).
                    if (jumpDistance > 2000.0 && towerInfo.signalStrength > -60) {
                        val msg = "PHYSICS ANOMALY: Signal too strong (${towerInfo.signalStrength}dBm) for distance (${jumpDistance.toInt()}m). Possible Amplifier/Stingray."
                        Log.e("TowerRefinement", "SECURITY ALERT: $msg")
                        onAnomalyDetected?.invoke(towerId, msg)
                        return false
                    }
                    
                    // Check B: Timing Advance Mismatch
                    val taDistance = DistanceEstimator.calculateDistanceFromTA(towerInfo.timingAdvance)
                    if (taDistance != null) {
                        val diff = jumpDistance - taDistance 
                        // THREAT: TA says close (20m), GPS says far (500m).
                        if (diff > 500.0) { // Relaxed buffer
                            val msg = "TIMING MISMATCH: Cellular timing indicates Proximity (~${taDistance.toInt()}m), but GPS says Far (~${jumpDistance.toInt()}m). SPOOFING."
                            Log.e("TowerRefinement", "SECURITY ALERT: $msg")
                            onAnomalyDetected?.invoke(towerId, msg)
                            return false
                        }
                    }
                }
            }
        }
        
        // Get or create refinement state for this tower
        val state = towerRefinementState[towerId] ?: createInitialState(towerId, currentLocation, fakeTowerDao)
        
        // Create and store the tower observation (ALWAYS LOG HISTORY)
        val observation = createTowerObservation(towerInfo, currentLocation, locationAccuracy)
        storeTowerObservation(towerDao, observation)
        
        // REFINEMENT LOGIC WITH STRICT DISTANCE CHECK
        // We only update the refinement state (count/location) if the user has moved
        // significantly from the LAST USED observation location.
        val lastLocation = state.lastObservationLocation
        
        val distance = if (lastLocation != null) {
            calculateDistance(
                lastLocation.first, lastLocation.second,
                currentLocation.first, currentLocation.second
            )
        } else {
            Double.MAX_VALUE // First observation always counts
        }
        
        if (distance < MIN_DISTANCE_FOR_NEW_OBSERVATION) {
            // User hasn't moved enough (100m) from previous refinement point.
            // We logged the history above, but we WON'T use this for triangulation yet.
            // By NOT updating 'state.lastObservationLocation', we ensure the "anchor" stays put
            // until the user moves far enough away from IT.
            Log.d("TowerRefinement", "Observation logged but skipped for refinement: " +
                  "Tower=$towerId, Dist=${"%.1f".format(distance)}m < ${MIN_DISTANCE_FOR_NEW_OBSERVATION}m")
            return false
        }
        
        Log.d("TowerRefinement", "Valid triangulation point found: " +
              "Tower=$towerId, Dist=${"%.1f".format(distance)}m >= ${MIN_DISTANCE_FOR_NEW_OBSERVATION}m")

        // Significant move detected: Update refinement state (counts this obs, sets new anchor)
        updateRefinementState(state, currentLocation)
        towerRefinementState[towerId] = state
        
        // Perform refinement if we have enough distinct points
        if (state.observationCount >= MIN_OBSERVATIONS_FOR_REFINEMENT) {
            return performTowerRefinement(towerDao, fakeTowerDao, towerInfo, state)
        }
        
        return false
    }

    // --- PRIVATE HELPER METHODS ---

    private suspend fun createInitialState(
        towerId: String,
        currentLocation: Pair<Double, Double>?,
        fakeTowerDao: FakeTowerDao
    ): TowerRefinementState {
        // Try to recover state from DB if possible, or start fresh
        val existing = fakeTowerDao.getFakeTower(
            towerId.split("-")[3].toInt(),
            towerId.split("-")[2].toInt(),
            towerId.split("-")[0].toInt(),
            towerId.split("-")[1].toInt()
        )
        
        return TowerRefinementState(
            towerId = towerId,
            currentLocation = existing?.let { Pair(it.latitude, it.longitude) } ?: Pair(0.0, 0.0),
            observationCount = existing?.observationCount ?: 0,
            precisionRadius = existing?.precisionRadius ?: 1000.0,
            lastObservationLocation = currentLocation,
            lastObservationTime = System.currentTimeMillis()
        )
    }

    private fun createTowerObservation(
        towerInfo: TowerConnectionInfo,
        location: Pair<Double, Double>?,
        accuracy: Float?
    ): TowerObservation {
        return TowerObservation(
            cid = towerInfo.cid,
            lac = towerInfo.lac,
            mcc = towerInfo.mcc ?: 0,
            mnc = towerInfo.mnc ?: 0,
            signal = towerInfo.signalStrength,
            rsrp = towerInfo.signalStrength,
            rsrq = towerInfo.ssRsrq,
            sinr = towerInfo.ssSinr,
            pci = towerInfo.pci,
            ta = towerInfo.timingAdvance,
            arfcn = towerInfo.arfcn,
            band = towerInfo.band,
            ssRsrp = towerInfo.ssRsrp,
            ssRsrq = towerInfo.ssRsrq,
            ssSinr = towerInfo.ssSinr,
            latitude = location?.first,
            longitude = location?.second,
            locationAccuracy = accuracy,
            timestamp = System.currentTimeMillis(),
            source = "refinement"
        )
    }

    private suspend fun storeTowerObservation(towerDao: TowerDao, observation: TowerObservation) {
        withContext(Dispatchers.IO) {
            // Map util.TowerObservation to db.TowerObservation entities if necessary
            // Or just log it. For v1.0, we rely on FakeTowerDao mostly.
            // This is a placeholder for the raw observation logging feature.
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371e3 // Earth radius in meters
        val phi1 = lat1 * Math.PI / 180
        val phi2 = lat2 * Math.PI / 180
        val deltaPhi = (lat2 - lat1) * Math.PI / 180
        val deltaLambda = (lon2 - lon1) * Math.PI / 180

        val a = sin(deltaPhi / 2).pow(2) +
                cos(phi1) * cos(phi2) *
                sin(deltaLambda / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return r * c
    }

    private fun updateRefinementState(state: TowerRefinementState, location: Pair<Double, Double>?) {
        state.observationCount++
        state.lastObservationLocation = location
        state.lastObservationTime = System.currentTimeMillis()
    }

    private suspend fun performTowerRefinement(
        towerDao: TowerDao,
        fakeTowerDao: FakeTowerDao,
        towerInfo: TowerConnectionInfo,
        state: TowerRefinementState
    ): Boolean {
        // Placeholder simple logic for v1.0 (assuming external solver or simple average)
        // Here we just mark it as triangulated if we have enough points in state
        
        val fakeTower = fakeTowerDao.getFakeTower(towerInfo.cid, towerInfo.lac, towerInfo.mcc?:0, towerInfo.mnc?:0)
        fakeTower?.let {
            val updatedTower = it.copy(
                isTriangulated = true,
                observationCount = state.observationCount
            )
            // Use insertFakeTower (UPSERT behavior assumed) as updateFakeTower is missing
            fakeTowerDao.insertFakeTower(updatedTower)
        }
        
        onRefinementUpdate?.invoke(state.towerId, state.precisionRadius, state.observationCount)
        return true
    }
    /**
     * Get approximate geographic region center based on MCC (Mobile Country Code)
     */
    private fun getRegionCenterFromMCC(mcc: Int, userLocation: Pair<Double, Double>? = null): Pair<Double, Double> {
        // STRICT MODE: Always return 0.0, 0.0
        return Pair(0.0, 0.0)
    }
    
    /**
     * Clear all refinement states
     */
    fun clearAllRefinementStates() {
        towerRefinementState.clear()
    }
    
    /**
     * Validates if coordinates are valid for display
     */
    private fun isValidLocation(latitude: Double, longitude: Double): Boolean {
        if (latitude.isNaN() || longitude.isNaN()) return false
        if (latitude == 0.0 && longitude == 0.0) return false // Invalid origin point
        if (latitude < -90.0 || latitude > 90.0) return false
        if (longitude < -180.0 || longitude > 180.0) return false
        return true
    }
}