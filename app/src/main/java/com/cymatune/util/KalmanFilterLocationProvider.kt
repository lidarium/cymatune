package com.cymatune.util

import android.location.Location
import android.util.Log
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A simple Kalman Filter implementation for fusing GPS and Accelerometer data
 * to provide a more accurate and smoothed location estimate.
 *
 * State vector X = [latitude, longitude, velocity_latitude, velocity_longitude]
 * Measurement vector Z = [latitude, longitude] (from GPS)
 * Control vector U = [accel_x, accel_y] (from accelerometer, after converting to lat/lon components)
 *
 * This is a simplified linear Kalman filter. For more complex scenarios (e.g., non-linear motion,
 * varying sensor noise), an Extended Kalman Filter (EKF) or Unscented Kalman Filter (UKF) would be needed.
 */
class KalmanFilterLocationProvider {

    // State vector: [latitude, longitude, velocity_latitude, velocity_longitude]
    private var x: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0, 0.0) // Initial state
    // Covariance matrix P: Represents the uncertainty of the state estimate
    private var P: Array<DoubleArray> = arrayOf(
        doubleArrayOf(1000.0, 0.0, 0.0, 0.0),
        doubleArrayOf(0.0, 1000.0, 0.0, 0.0),
        doubleArrayOf(0.0, 0.0, 1000.0, 0.0),
        doubleArrayOf(0.0, 0.0, 0.0, 1000.0)
    )

    // State transition matrix F: Describes how the state evolves from t-1 to t
    // F = [[1, 0, dt, 0],
    //      [0, 1, 0, dt],
    //      [0, 0, 1, 0],
    //      [0, 0, 0, 1]]
    private var F: Array<DoubleArray> = arrayOf(
        doubleArrayOf(1.0, 0.0, 0.0, 0.0),
        doubleArrayOf(0.0, 1.0, 0.0, 0.0),
        doubleArrayOf(0.0, 0.0, 1.0, 0.0),
        doubleArrayOf(0.0, 0.0, 0.0, 1.0)
    )

    // Process noise covariance matrix Q: Represents uncertainty in the process model (e.g., unmodeled accelerations)
    // Tune these values based on expected system dynamics and noise
    private var Q: Array<DoubleArray> = arrayOf(
        doubleArrayOf(0.1, 0.0, 0.0, 0.0),
        doubleArrayOf(0.0, 0.1, 0.0, 0.0),
        doubleArrayOf(0.0, 0.0, 0.01, 0.0),
        doubleArrayOf(0.0, 0.0, 0.0, 0.01)
    )

    // Measurement matrix H: Relates the state to the measurement (Z = H * X)
    // H = [[1, 0, 0, 0],
    //      [0, 1, 0, 0]] (We only measure position directly from GPS)
    private var H: Array<DoubleArray> = arrayOf(
        doubleArrayOf(1.0, 0.0, 0.0, 0.0),
        doubleArrayOf(0.0, 1.0, 0.0, 0.0)
    )

    // Measurement noise covariance matrix R: Represents uncertainty in the GPS measurements
    // Tune these values based on GPS accuracy
    private var R: Array<DoubleArray> = arrayOf(
        doubleArrayOf(10.0, 0.0), // Latitude measurement noise
        doubleArrayOf(0.0, 10.0)  // Longitude measurement noise
    )

    private var lastTimestamp: Long = 0L

    /**
     * Initializes the filter with the first GPS location.
     */
    fun initialize(location: Location) {
        x[0] = location.latitude
        x[1] = location.longitude
        lastTimestamp = location.time
        // Reset P to a smaller initial uncertainty after first valid location
        P = arrayOf(
            doubleArrayOf(10.0, 0.0, 0.0, 0.0),
            doubleArrayOf(0.0, 10.0, 0.0, 0.0),
            doubleArrayOf(0.0, 0.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0, 1.0)
        )
        Log.d("KalmanFilter", "Initialized with Lat: ${x[0]}, Lon: ${x[1]}")
    }

    /**
     * Predicts the next state based on the system model and accelerometer data.
     * Call this frequently with accelerometer updates.
     */
    fun predict(accelerometerData: AccelerometerData) {
        if (lastTimestamp == 0L) {
            Log.w("KalmanFilter", "Filter not initialized. Skipping prediction.")
            return
        }

        val dt = (accelerometerData.timestamp - lastTimestamp) / 1_000_000_000.0 // Time step in seconds (nanoseconds to seconds)
        if (dt <= 0) return // No time elapsed or invalid timestamp

        // Update state transition matrix F with current dt
        F[0][2] = dt
        F[1][3] = dt

        // Convert accelerometer data (m/s^2) to lat/lon acceleration components
        // This is a simplification. Proper conversion requires bearing/orientation.
        // For now, assume accel_x roughly aligns with lat accel, accel_y with lon accel.
        // Need to convert m/s^2 to degrees/s^2.
        // 1 degree latitude ~ 111,320 meters
        // 1 degree longitude ~ 111,320 * cos(latitude) meters
        val metersPerLatDegree = 111320.0
        val metersPerLonDegree = 111320.0 * kotlin.math.cos(Math.toRadians(x[0]))

        val accelLat = accelerometerData.y / metersPerLatDegree // Simplified: y-axis accel affects latitude
        val accelLon = accelerometerData.x / metersPerLonDegree // Simplified: x-axis accel affects longitude

        // Control vector B * U (B is identity for direct acceleration input to velocity)
        // B = [[0, 0],
        //      [0, 0],
        //      [1, 0],
        //      [0, 1]]
        val B_U = doubleArrayOf(
            0.0,
            0.0,
            accelLat * dt, // Change in velocity due to acceleration
            accelLon * dt
        )

        // Predict state: x = F * x + B * U
        val x_predicted = doubleArrayOf(
            F[0][0] * x[0] + F[0][1] * x[1] + F[0][2] * x[2] + F[0][3] * x[3] + B_U[0],
            F[1][0] * x[0] + F[1][1] * x[1] + F[1][2] * x[2] + F[1][3] * x[3] + B_U[1],
            F[2][0] * x[0] + F[2][1] * x[1] + F[2][2] * x[2] + F[2][3] * x[3] + B_U[2],
            F[3][0] * x[0] + F[3][1] * x[1] + F[3][2] * x[2] + F[3][3] * x[3] + B_U[3]
        )

        // Predict covariance: P = F * P * F_transpose + Q
        val F_transpose = transpose(F)
        val F_P = multiply(F, P)
        val F_P_F_transpose = multiply(F_P, F_transpose)
        val P_predicted = add(F_P_F_transpose, Q)

        x = x_predicted
        P = P_predicted
        lastTimestamp = accelerometerData.timestamp
        Log.d("KalmanFilter", "Predicted Lat: ${x[0]}, Lon: ${x[1]}, VelLat: ${x[2]}, VelLon: ${x[3]}")
    }

    /**
     * Updates the state based on a new GPS measurement.
     * Call this when a new GPS location is available.
     */
    fun processLocation(latitude: Double, longitude: Double, accuracy: Double, timestamp: Long) {
        if (lastTimestamp == 0L) {
            // Validate coordinates before initialization
            if (isValidLocation(latitude, longitude)) {
                initialize(Location("gps").apply {
                    this.latitude = latitude
                    this.longitude = longitude
                    this.accuracy = accuracy.toFloat()
                    this.time = timestamp
                })
            } else {
                Log.w("KalmanFilter", "Invalid coordinates provided for initialization: Lat=$latitude, Lon=$longitude")
                // Use a small offset from origin to avoid 0.0, 0.0 coordinates
                val safeLat = if (latitude == 0.0) 0.001 else latitude
                val safeLon = if (longitude == 0.0) 0.001 else longitude
                initialize(Location("gps").apply {
                    this.latitude = safeLat
                    this.longitude = safeLon
                    this.accuracy = accuracy.toFloat()
                    this.time = timestamp
                })
            }
            return
        }

        // Measurement vector Z
        val z = doubleArrayOf(latitude, longitude)

        // Adjust R based on reported accuracy
        R[0][0] = accuracy.pow(2.0) // Variance for latitude
        R[1][1] = accuracy.pow(2.0) // Variance for longitude

        // Innovation (measurement residual): y = Z - H * x
        val H_x = multiply(H, x)
        val y = subtract(z, H_x)

        // Innovation (or residual) covariance: S = H * P * H_transpose + R
        val H_transpose = transpose(H)
        val H_P = multiply(H, P)
        val H_P_H_transpose = multiply(H_P, H_transpose)
        val S = add(H_P_H_transpose, R)

        // Kalman Gain: K = P * H_transpose * S_inverse
        val S_inverse = inverse(S)
        val P_H_transpose = multiply(P, H_transpose)
        val K = multiply(P_H_transpose, S_inverse)

        // Update state: x = x + K * y
        val K_y = multiply(K, y)
        x = add(x, K_y)

        // Update covariance: P = (I - K * H) * P
        val I = identity(P.size)
        val K_H = multiply(K, H)
        val I_minus_K_H = subtract(I, K_H)
        P = multiply(I_minus_K_H, P)

        lastTimestamp = timestamp
        Log.d("KalmanFilter", "Updated Lat: ${x[0]}, Lon: ${x[1]}, Accuracy: $accuracy")
    }

    /**
     * Returns the current estimated location.
     */
    fun getEstimatedLocation(): Pair<Double, Double> {
        return Pair(x[0], x[1])
    }

    // --- Matrix Operations (Simplified for 2D/4D) ---

    private fun multiply(matrix: Array<DoubleArray>, vector: DoubleArray): DoubleArray {
        val result = DoubleArray(matrix.size)
        for (i in matrix.indices) {
            for (j in matrix[0].indices) {
                result[i] += matrix[i][j] * vector[j]
            }
        }
        return result
    }

    private fun multiply(matrix1: Array<DoubleArray>, matrix2: Array<DoubleArray>): Array<DoubleArray> {
        val resultRows = matrix1.size
        val resultCols = matrix2[0].size
        val commonDim = matrix1[0].size
        val result = Array(resultRows) { DoubleArray(resultCols) }

        for (i in 0 until resultRows) {
            for (j in 0 until resultCols) {
                for (k in 0 until commonDim) {
                    result[i][j] += matrix1[i][k] * matrix2[k][j]
                }
            }
        }
        return result
    }

    private fun add(vector1: DoubleArray, vector2: DoubleArray): DoubleArray {
        val result = DoubleArray(vector1.size)
        for (i in vector1.indices) {
            result[i] = vector1[i] + vector2[i]
        }
        return result
    }

    private fun add(matrix1: Array<DoubleArray>, matrix2: Array<DoubleArray>): Array<DoubleArray> {
        val result = Array(matrix1.size) { DoubleArray(matrix1[0].size) }
        for (i in matrix1.indices) {
            for (j in matrix1[0].indices) {
                result[i][j] = matrix1[i][j] + matrix2[i][j]
            }
        }
        return result
    }

    private fun subtract(vector1: DoubleArray, vector2: DoubleArray): DoubleArray {
        val result = DoubleArray(vector1.size)
        for (i in vector1.indices) {
            result[i] = vector1[i] - vector2[i]
        }
        return result
    }

    private fun subtract(matrix1: Array<DoubleArray>, matrix2: Array<DoubleArray>): Array<DoubleArray> {
        val result = Array(matrix1.size) { DoubleArray(matrix1[0].size) }
        for (i in matrix1.indices) {
            for (j in matrix1[0].indices) {
                result[i][j] = matrix1[i][j] - matrix2[i][j]
            }
        }
        return result
    }

    private fun transpose(matrix: Array<DoubleArray>): Array<DoubleArray> {
        val rows = matrix.size
        val cols = matrix[0].size
        val result = Array(cols) { DoubleArray(rows) }
        for (i in 0 until rows) {
            for (j in 0 until cols) {
                result[j][i] = matrix[i][j]
            }
        }
        return result
    }

    private fun identity(size: Int): Array<DoubleArray> {
        val result = Array(size) { DoubleArray(size) }
        for (i in 0 until size) {
            result[i][i] = 1.0
        }
        return result
    }

    // Simplified 2x2 matrix inverse for S (assuming S is 2x2)
    private fun inverse(matrix: Array<DoubleArray>): Array<DoubleArray> {
        if (matrix.size != 2 || matrix[0].size != 2) {
            throw IllegalArgumentException("Inverse only implemented for 2x2 matrices.")
        }
        val det = matrix[0][0] * matrix[1][1] - matrix[0][1] * matrix[1][0]
        if (det == 0.0) {
            throw IllegalArgumentException("Matrix is singular, cannot invert.")
        }
        val invDet = 1.0 / det
        return arrayOf(
            doubleArrayOf(matrix[1][1] * invDet, -matrix[0][1] * invDet),
            doubleArrayOf(-matrix[1][0] * invDet, matrix[0][0] * invDet)
        )
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
