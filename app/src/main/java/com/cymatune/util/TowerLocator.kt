package com.cymatune.util

import android.util.Log
import com.cymatune.util.DistanceEstimator
import com.cymatune.util.TowerObservation
import com.cymatune.util.CommonUtils
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.abs

/**
 * Tower localization algorithms for estimating cell tower positions using multiple observations.
 *
 * Implements advanced triangulation algorithms including:
 * - Levenberg-Marquardt optimization for non-linear least squares
 * - Weighted averaging based on signal quality
 * - Convergence detection and validation
 * - Robust error handling and fallback mechanisms
 */
object TowerLocator {

    // Minimum observations required for reliable triangulation
    internal const val MIN_OBSERVATIONS_FOR_LOCALIZATION = 3
    
    // Algorithm convergence parameters
    private const val MAX_ITERATIONS = 50
    private const val CONVERGENCE_THRESHOLD = 1e-7
    private const val INITIAL_LAMBDA = 0.001
    private const val LAMBDA_UP_FACTOR = 10.0
    private const val LAMBDA_DOWN_FACTOR = 10.0
    
    // Location accuracy requirements (meters)
    private const val LOCATION_ACCURACY_THRESHOLD = 50.0
    
    // Sanity check thresholds
    private const val MAX_REASONABLE_DISTANCE_KM = 50.0 // Maximum distance for sanity check
    private const val EARTH_RADIUS_METERS = 6371000.0

    fun estimateTowerLocation(observations: List<TowerObservation>): Pair<Double, Double>? {
        val validObservations = observations.filter {
            it.latitude != null && it.longitude != null &&
            it.locationAccuracy != null && it.locationAccuracy <= LOCATION_ACCURACY_THRESHOLD &&
            DistanceEstimator.calculateEstimatedDistanceToTower(it.ta, it.rsrp, it.rsrq, it.sinr) != null
        }.distinctBy { Pair(it.latitude, it.longitude) }

        if (validObservations.size < MIN_OBSERVATIONS_FOR_LOCALIZATION) {
            Log.d("TowerLocator", "Not enough valid observations for localization: ${validObservations.size}")
            return null
        }

        // Use weighted average based on signal quality instead of simple average
        var currentLat = calculateWeightedAverageLatitude(validObservations)
        var currentLon = calculateWeightedAverageLongitude(validObservations)
        var lambda = INITIAL_LAMBDA
        var lastCost = Double.MAX_VALUE
        var converged = false

        for (iteration in 0 until MAX_ITERATIONS) {
            val (cost, jacobian, residuals) = calculateJacobianAndResiduals(currentLat, currentLon, validObservations)

            if (abs(lastCost - cost) < CONVERGENCE_THRESHOLD) {
                Log.d("TowerLocator", "Converged at iteration $iteration. Final Cost: $cost")
                converged = true
                break
            }

            val jtJ = jacobian.transpose().multiply(jacobian)
            val jtJPlusLambdaI = jtJ.add(Matrix.identity(2).scalarMultiply(lambda))
            val jtR = jacobian.transpose().multiply(residuals)

            try {
                val delta = jtJPlusLambdaI.inverse().multiply(jtR)
                val (dLat, dLon) = metersToLatLon(delta[0][0], delta[1][0], currentLat)
                val newLat = currentLat - dLat
                val newLon = currentLon - dLon

                val (newCost, _, _) = calculateJacobianAndResiduals(newLat, newLon, validObservations)

                if (newCost < cost) {
                    currentLat = newLat
                    currentLon = newLon
                    lambda /= LAMBDA_DOWN_FACTOR
                    lastCost = newCost
                } else {
                    lambda *= LAMBDA_UP_FACTOR
                }
            } catch (e: Exception) {
                Log.e("TowerLocator", "Error during matrix inversion: ${e.message}")
                lambda *= LAMBDA_UP_FACTOR
            }
        }

        if (!converged) {
            Log.d("TowerLocator", "Did not converge after $MAX_ITERATIONS iterations.")
            return null
        }

        // Sanity check the result
        val weightedAvgObsLat = calculateWeightedAverageLatitude(validObservations)
        val weightedAvgObsLon = calculateWeightedAverageLongitude(validObservations)
        val distance = DistanceEstimator.calculateHaversineDistance(currentLat, currentLon, weightedAvgObsLat, weightedAvgObsLon)
        if (distance > 50000) { // 50km
            Log.d("TowerLocator", "Estimated location is too far from observations: $distance meters")
            return null
        }

        return Pair(currentLat, currentLon)
    }

    private fun calculateJacobianAndResiduals(
        towerLat: Double,
        towerLon: Double,
        observations: List<TowerObservation>
    ): Triple<Double, Matrix, Matrix> {
        val n = observations.size
        val jacobian = Matrix(n, 2)
        val residuals = Matrix(n, 1)
        var totalCost = 0.0

        for (i in 0 until n) {
            val obs = observations[i]
            val obsLat = obs.latitude ?: return Triple(totalCost, jacobian, residuals)
            val obsLon = obs.longitude ?: return Triple(totalCost, jacobian, residuals)
            val estimatedDistance = DistanceEstimator.calculateHaversineDistance(obsLat, obsLon, towerLat, towerLon)
            val observedDistance = DistanceEstimator.calculateEstimatedDistanceToTower(obs.ta, obs.rsrp, obs.rsrq, obs.sinr) ?: return Triple(totalCost, jacobian, residuals)

            val residual = observedDistance - estimatedDistance
            residuals[i][0] = residual
            totalCost += residual.pow(2)

            if (estimatedDistance > 1e-6) {
                val (dx, dy) = latLonToMeters(towerLat - obsLat, towerLon - obsLon, obsLat)
                jacobian[i][0] = dx / estimatedDistance
                jacobian[i][1] = dy / estimatedDistance
            } else {
                jacobian[i][0] = 0.0
                jacobian[i][1] = 0.0
            }
        }
        return Triple(totalCost, jacobian, residuals)
    }

    private fun latLonToMeters(dLat: Double, dLon: Double, lat: Double): Pair<Double, Double> {
        val r = 6371000.0 // Earth radius in meters
        val dx = dLon * r * kotlin.math.cos(Math.toRadians(lat))
        val dy = dLat * r
        return Pair(dx, dy)
    }

    private fun metersToLatLon(dx: Double, dy: Double, lat: Double): Pair<Double, Double> {
        val r = 6371000.0 // Earth radius in meters
        val dLat = dy / r
        val dLon = dx / (r * kotlin.math.cos(Math.toRadians(lat)))
        return Pair(dLat, dLon)
    }

    /**
     * Calculate weighted average latitude based on signal quality metrics
     * Stronger signals and better quality observations get higher weights
     */
    private fun calculateWeightedAverageLatitude(observations: List<TowerObservation>): Double {
        val weights = observations.map { calculateObservationWeight(it) }
        val totalWeight = weights.sum()
        
        if (totalWeight == 0.0) {
            return observations.mapNotNull { it.latitude }.average()
        }
        
        var weightedSum = 0.0
        for (i in observations.indices) {
            val lat = observations[i].latitude ?: continue
            weightedSum += lat * weights[i]
        }
        
        return weightedSum / totalWeight
    }

    /**
     * Calculate weighted average longitude based on signal quality metrics
     */
    private fun calculateWeightedAverageLongitude(observations: List<TowerObservation>): Double {
        val weights = observations.map { calculateObservationWeight(it) }
        val totalWeight = weights.sum()
        
        if (totalWeight == 0.0) {
            return observations.mapNotNull { it.longitude }.average()
        }
        
        var weightedSum = 0.0
        for (i in observations.indices) {
            val lon = observations[i].longitude ?: continue
            weightedSum += lon * weights[i]
        }
        
        return weightedSum / totalWeight
    }

    /**
     * Calculate observation weight based on signal quality metrics
     * Higher signal strength and better quality metrics = higher weight
     *
     * @param observation Tower observation to calculate weight for
     * @return Weight value between 0.1 and 1.0
     */
    private fun calculateObservationWeight(observation: TowerObservation): Double {
        var weight = 1.0 // Base weight
        
        // Signal strength weighting (-50 dBm = excellent, -120 dBm = poor)
        if (observation.signal != Int.MIN_VALUE) {
            val signalStrength = observation.signal
            // Normalize signal strength to 0-1 range (better signal = higher weight)
            val signalWeight = when {
                signalStrength >= -50 -> 1.0  // Excellent signal
                signalStrength >= -80 -> 0.8  // Good signal
                signalStrength >= -100 -> 0.5 // Fair signal
                signalStrength >= -110 -> 0.3 // Poor signal
                else -> 0.1                   // Very poor signal
            }
            weight *= signalWeight
        }
        
        // RSRP weighting for LTE/NR (higher RSRP = better signal)
        if (observation.rsrp != Int.MIN_VALUE) {
            val rsrp = observation.rsrp
            val rsrpWeight = when {
                rsrp >= -80 -> 1.0   // Excellent RSRP
                rsrp >= -90 -> 0.8   // Good RSRP
                rsrp >= -100 -> 0.6  // Fair RSRP
                rsrp >= -110 -> 0.4  // Poor RSRP
                else -> 0.2          // Very poor RSRP
            }
            weight *= rsrpWeight
        }
        
        // Location accuracy weighting (better accuracy = higher weight)
        if (observation.locationAccuracy != null) {
            val accuracy = observation.locationAccuracy
            val accuracyWeight = when {
                accuracy <= 10.0 -> 1.0   // High accuracy (<=10m)
                accuracy <= 30.0 -> 0.8   // Good accuracy (<=30m)
                accuracy <= 50.0 -> 0.6   // Fair accuracy (<=50m)
                accuracy <= 100.0 -> 0.4  // Poor accuracy (<=100m)
                else -> 0.2               // Very poor accuracy (>100m)
            }
            weight *= accuracyWeight
        }
        
        // RSRQ weighting for signal quality (higher RSRQ = better quality)
        observation.rsrq?.let { rsrq ->
            val rsrqWeight = when {
                rsrq >= -5 -> 1.0    // Excellent RSRQ
                rsrq >= -10 -> 0.9   // Good RSRQ
                rsrq >= -15 -> 0.7   // Fair RSRQ
                rsrq >= -20 -> 0.5   // Poor RSRQ
                else -> 0.3          // Very poor RSRQ
            }
            weight *= rsrqWeight
        }
        
        // SINR weighting for signal-to-noise ratio (higher SINR = better)
        observation.sinr?.let { sinr ->
            val sinrWeight = when {
                sinr >= 20 -> 1.0    // Excellent SINR
                sinr >= 10 -> 0.9    // Good SINR
                sinr >= 5 -> 0.7     // Fair SINR
                sinr >= 0 -> 0.5     // Poor SINR
                else -> 0.3          // Very poor SINR
            }
            weight *= sinrWeight
        }
        
        return weight.coerceAtLeast(0.1) // Ensure minimum weight to avoid division by zero
    }
    
    /**
     * Validate triangulation result for reasonableness
     *
     * @param estimatedLocation Estimated tower location
     * @param observations Original observations used for triangulation
     * @return True if result is reasonable, false otherwise
     */
    private fun validateTriangulationResult(
        estimatedLocation: Pair<Double, Double>,
        observations: List<TowerObservation>
    ): Boolean {
        // Calculate weighted average of observations for comparison
        val weightedAvgLat = calculateWeightedAverageLatitude(observations)
        val weightedAvgLon = calculateWeightedAverageLongitude(observations)
        
        // Check if estimated location is within reasonable distance from observations
        val distance = CommonUtils.calculateDistance(
            estimatedLocation.first, estimatedLocation.second,
            weightedAvgLat, weightedAvgLon
        )
        
        val maxReasonableDistance = MAX_REASONABLE_DISTANCE_KM * 1000 // Convert to meters
        val isReasonable = distance <= maxReasonableDistance
        
        if (!isReasonable) {
            Log.w("TowerLocator", "Triangulation result rejected: " +
                  "estimated location too far from observations ($distance meters)")
        }
        
        return isReasonable
    }
}

/**
 * Simple matrix implementation for Levenberg-Marquardt algorithm
 * Optimized for small matrices (2x2) commonly used in tower localization
 */
class Matrix(val rows: Int, val cols: Int) {
    val data = Array(rows) { DoubleArray(cols) }

    operator fun get(i: Int): DoubleArray = data[i]

    /**
     * Transpose the matrix
     */
    fun transpose(): Matrix {
        val result = Matrix(cols, rows)
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                result[j][i] = data[i][j]
            }
        }
        return result
    }

    /**
     * Multiply this matrix with another matrix
     */
    fun multiply(other: Matrix): Matrix {
        require(cols == other.rows) { "Matrix dimensions do not match for multiplication: ${rows}x${cols} * ${other.rows}x${other.cols}" }
        val result = Matrix(rows, other.cols)
        for (i in 0 until rows) {
            for (j in 0 until other.cols) {
                for (k in 0 until cols) {
                    result[i][j] += data[i][k] * other.data[k][j]
                }
            }
        }
        return result
    }

    /**
     * Add another matrix to this matrix
     */
    fun add(other: Matrix): Matrix {
        require(rows == other.rows && cols == other.cols) { "Matrix dimensions do not match for addition: ${rows}x${cols} + ${other.rows}x${other.cols}" }
        val result = Matrix(rows, cols)
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                result[i][j] = data[i][j] + other.data[i][j]
            }
        }
        return result
    }

    /**
     * Multiply matrix by a scalar value
     */
    fun scalarMultiply(scalar: Double): Matrix {
        val result = Matrix(rows, cols)
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                result[i][j] = data[i][j] * scalar
            }
        }
        return result
    }

    /**
     * Invert the matrix (only supports 2x2 matrices for performance)
     */
    fun inverse(): Matrix {
        require(rows == cols) { "Only square matrices can be inverted" }
        require(rows == 2) { "Only 2x2 matrix inversion is supported for performance" }
        
        val det = data[0][0] * data[1][1] - data[0][1] * data[1][0]
        require(abs(det) >= 1e-10) { "Matrix is singular and cannot be inverted (determinant: $det)" }
        
        val result = Matrix(2, 2)
        result[0][0] = data[1][1] / det
        result[0][1] = -data[0][1] / det
        result[1][0] = -data[1][0] / det
        result[1][1] = data[0][0] / det
        return result
    }

    companion object {
        /**
         * Create an identity matrix of given size
         */
        fun identity(size: Int): Matrix {
            require(size > 0) { "Identity matrix size must be positive" }
            val result = Matrix(size, size)
            for (i in 0 until size) {
                result[i][i] = 1.0
            }
            return result
        }
    }
}
