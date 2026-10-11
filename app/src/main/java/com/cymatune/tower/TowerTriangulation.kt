package com.cymatune.tower

import com.cymatune.util.TowerObservation
import kotlin.math.*

object TowerTriangulation {
    fun estimateTowerLocation(observations: List<TowerObservation>): Pair<Double, Double>? {
        if (observations.isEmpty()) return null
        var sumLat = 0.0
        var sumLon = 0.0
        var sumWeight = 0.0
        for (obs in observations) {
            val weight = computeWeight(obs)
            if (obs.latitude != null && obs.longitude != null) {
                sumLat += obs.latitude * weight
                sumLon += obs.longitude * weight
                sumWeight += weight
            }
        }
        return if (sumWeight > 0) Pair(sumLat / sumWeight, sumLon / sumWeight) else null
    }

    fun estimateTowerLocationMultilateration(observations: List<TowerObservation>): Pair<Double, Double>? {
        // Only use observations with lat/lon and valid distance parameters
        val validObs = observations.filter {
            it.latitude != null && it.longitude != null && (it.ta != null || it.rsrp != Int.MIN_VALUE)
        }
        
        // Use nonlinear least squares multilateration
        val points = validObs.map { obs ->
            val distance = if (obs.ta != null) {
                obs.ta?.let { taToDistance(it) } ?: 1000.0 // Default to 1km if TA is null
            } else {
                // Use DistanceEstimator with available parameters
                com.cymatune.util.DistanceEstimator.calculateEstimatedDistanceToTower(
                    obs.ta, obs.rsrp, obs.rsrq, obs.sinr) ?: 1000.0 // Default to 1km if estimation fails
            }
            Triple(obs.latitude ?: 0.0, obs.longitude ?: 0.0, distance)
        }
        
        // Initial guess: centroid
        var x = points.map { it.first }.average()
        var y = points.map { it.second }.average()
        // Simple iterative refinement (gradient descent)
        repeat(100) {
            var gradX = 0.0
            var gradY = 0.0
            for ((lat, lon, dist) in points) {
                val dx = x - lat
                val dy = y - lon
                val d = sqrt(dx*dx + dy*dy)
                if (d > 1e-6) {
                    gradX += 2 * (d - dist) * (dx / d)
                    gradY += 2 * (d - dist) * (dy / d)
                }
            }
            x -= 0.0001 * gradX
            y -= 0.0001 * gradY
        }
        return Pair(x, y)
    }

    private fun computeWeight(obs: TowerObservation): Double {
        // Stronger signal and better quality metrics = higher weight
        val signalWeight = obs.signal?.toDouble()?.let { 10 + (it / 100.0) } ?: 1.0
        val taWeight = obs.ta?.let { 10 / (it / 10.0) } ?: 1.0
        
        // Weight based on RSRQ and SINR if available
        val rsrqWeight = obs.rsrq?.let { rsrq ->
            // Normalize RSRQ to 0-1 range (higher is better)
            // RSRQ typical range: -20 dB (worst) to -3 dB (best)
            ((rsrq + 3.0) / 17.0).coerceIn(0.0, 1.0)
        } ?: 1.0
        
        val sinrWeight = obs.sinr?.let { sinr ->
            // Normalize SINR to 0-1 range (higher is better)
            // SINR typical range: -10 dB (worst) to 30 dB (best)
            ((sinr + 10.0) / 40.0).coerceIn(0.0, 1.0)
        } ?: 1.0
        
        // Combined quality weight (geometric mean)
        val qualityWeight = sqrt(rsrqWeight * sinrWeight)
        
        return signalWeight * taWeight * (0.5 + 0.5 * qualityWeight) // Quality weight ranges from 0.5 to 1.0
    }

    fun taToDistance(ta: Int): Double {
        // 1 TA = 78 meters (LTE)
        return ta * 78.0
    }

    /**
     * Quick triangulation method for currently connected towers
     * Uses device's current location and signal strength to estimate tower position
     * 
     * IMPORTANT: When direction is unknown, we return the user's location as the
     * estimated center with an uncertainty radius. This is more accurate than
     * assuming a fixed direction (which was the previous behavior).
     * 
     * @return TowerLocationEstimate with center point and uncertainty radius
     */
    fun quickTriangulateConnectedTower(
        currentLat: Double,
        currentLon: Double,
        signalStrength: Int,
        ta: Int? = null,
        compassBearing: Float? = null  // Optional: device compass heading for direction inference
    ): TowerLocationEstimate {
        // Validate input coordinates
        if (!isValidLocation(currentLat, currentLon)) {
            // If device location is invalid, return unknown estimate
            return TowerLocationEstimate(
                centerLat = 0.0,
                centerLon = 0.0,
                uncertaintyRadius = 10000.0,  // 10km uncertainty when location invalid
                confidence = 0.1,
                directionKnown = false
            )
        }
        
        // Calculate estimated distance to tower
        val estimatedDistance = when {
            ta != null && ta > 0 -> taToDistance(ta)  // Most accurate
            else -> estimateDistanceFromSignal(signalStrength)
        }
        
        // If we have compass bearing, we can estimate direction
        if (compassBearing != null) {
            val bearingRad = Math.toRadians(compassBearing.toDouble())
            val estimatedLat = currentLat + (estimatedDistance / 111320.0) * cos(bearingRad)
            val estimatedLon = currentLon + (estimatedDistance / (111320.0 * cos(currentLat * Math.PI / 180))) * sin(bearingRad)
            
            if (isValidLocation(estimatedLat, estimatedLon)) {
                return TowerLocationEstimate(
                    centerLat = estimatedLat,
                    centerLon = estimatedLon,
                    uncertaintyRadius = estimatedDistance * 0.3,  // 30% uncertainty
                    confidence = 0.7,
                    directionKnown = true
                )
            }
        }
        
        // Without direction information, return user's location as center
        // with the estimated distance as the uncertainty radius
        // This creates a circle of possible tower locations
        return TowerLocationEstimate(
            centerLat = currentLat,
            centerLon = currentLon,
            uncertaintyRadius = estimatedDistance,
            confidence = 0.4,  // Lower confidence when direction unknown
            directionKnown = false
        )
    }
    
    /**
     * Legacy method for backward compatibility
     * Returns Pair<lat, lon> - callers should migrate to the new method that returns TowerLocationEstimate
     */
    @Deprecated("Use quickTriangulateConnectedTower returning TowerLocationEstimate instead")
    fun quickTriangulateConnectedTowerLegacy(
        currentLat: Double,
        currentLon: Double,
        signalStrength: Int,
        ta: Int? = null
    ): Pair<Double, Double> {
        val estimate = quickTriangulateConnectedTower(currentLat, currentLon, signalStrength, ta)
        return Pair(estimate.centerLat, estimate.centerLon)
    }

    private fun estimateDistanceFromSignal(signalStrength: Int): Double {
        // Simplified distance estimation based on signal strength
        // Stronger signal = closer distance
        return when {
            signalStrength >= -70 -> 100.0 // Very strong signal: ~100m
            signalStrength >= -85 -> 300.0 // Strong signal: ~300m
            signalStrength >= -100 -> 1000.0 // Moderate signal: ~1km
            signalStrength >= -110 -> 2000.0 // Weak signal: ~2km
            else -> 5000.0 // Very weak signal: ~5km
        }
    }
 
    /**
     * Validates if coordinates are valid for triangulation
     */
    private fun isValidLocation(latitude: Double, longitude: Double): Boolean {
        if (latitude.isNaN() || longitude.isNaN()) return false
        if (latitude == 0.0 && longitude == 0.0) return false // Invalid origin point
        if (latitude < -90.0 || latitude > 90.0) return false
        if (longitude < -180.0 || longitude > 180.0) return false
        return true
    }
    
    /**
     * Result of tower location estimation
     */
    data class TowerLocationEstimate(
        val centerLat: Double,          // Estimated tower latitude (or center of uncertainty circle)
        val centerLon: Double,          // Estimated tower longitude (or center of uncertainty circle)
        val uncertaintyRadius: Double,  // Radius of uncertainty in meters
        val confidence: Double,         // Confidence score 0.0-1.0
        val directionKnown: Boolean     // Whether direction to tower was inferred
    )
}
