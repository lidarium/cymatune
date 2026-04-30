package com.cymatune.detection

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

/**
 * MovementDetector uses the device's accelerometer to detect user movement.
 * This prevents false positives when the user is traveling and encounters new cell towers.
 * 
 * Movement States:
 * - STATIONARY: Device is still (< 2 m/s²)
 * - WALKING: Light movement (2-4 m/s²)
 * - MOVING: Active movement (4-8 m/s²)
 * - TRAVELING: Significant movement (> 8 m/s²)
 */
class MovementDetector(private val context: Context) : SensorEventListener {
    
    private val sensorManager: SensorManager = 
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    
    private val accelerometer: Sensor? = 
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    
    // Movement state
    var isMoving: Boolean = false
        private set
    
    var movementIntensity: MovementIntensity = MovementIntensity.STATIONARY
        private set
    
    // Movement detection parameters
    private var lastUpdate: Long = 0
    private var last_x = 0f
    private var last_y = 0f
    private var last_z = 0f
    
    // Smoothing for noise reduction
    private val accelerationHistory = mutableListOf<Float>()
    private val HISTORY_SIZE = 10
    
    // Thresholds (in m/s²)
    private val MOVEMENT_THRESHOLD_STATIONARY = 2.0f
    private val MOVEMENT_THRESHOLD_WALKING = 4.0f
    private val MOVEMENT_THRESHOLD_MOVING = 8.0f
    private val UPDATE_INTERVAL_MS = 100L  // Update every 100ms
    
    // Callbacks
    private var onMovementStateChanged: ((Boolean, MovementIntensity) -> Unit)? = null
    
    enum class MovementIntensity {
        STATIONARY,    // 0-2 m/s²
        WALKING,       // 2-4 m/s²
        MOVING,        // 4-8 m/s²
        TRAVELING      // > 8 m/s²
    }
    
    companion object {
        private const val TAG = "MovementDetector"
    }
    
    /**
     * Start monitoring movement
     */
    fun start() {
        if (accelerometer == null) {
            Log.w(TAG, "Accelerometer sensor not available on this device")
            return
        }
        
        sensorManager.registerListener(
            this,
            accelerometer,
            SensorManager.SENSOR_DELAY_NORMAL
        )
        
        Log.i(TAG, "Movement detection started")
    }
    
    /**
     * Stop monitoring movement
     */
    fun stop() {
        sensorManager.unregisterListener(this)
        Log.i(TAG, "Movement detection stopped")
    }
    
    /**
     * Set callback for movement state changes
     */
    fun setOnMovementStateChangedListener(listener: (Boolean, MovementIntensity) -> Unit) {
        onMovementStateChanged = listener
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        
        val currentTime = System.currentTimeMillis()
        
        // Rate limit updates
        if (currentTime - lastUpdate < UPDATE_INTERVAL_MS) {
            return
        }
        
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        
        // Calculate change in acceleration (delta)
        val deltaX = x - last_x
        val deltaY = y - last_y
        val deltaZ = z - last_z
        
        // Calculate acceleration magnitude (excluding gravity)
        val accelerationMagnitude = sqrt(
            deltaX * deltaX + 
            deltaY * deltaY + 
            deltaZ * deltaZ
        )
        
        // Add to history for smoothing
        accelerationHistory.add(accelerationMagnitude)
        if (accelerationHistory.size > HISTORY_SIZE) {
            accelerationHistory.removeAt(0)
        }
        
        // Calculate smoothed average
        val smoothedAcceleration = if (accelerationHistory.isNotEmpty()) {
            accelerationHistory.average().toFloat()
        } else {
            accelerationMagnitude
        }
        
        // Determine movement intensity
        val previousIntensity = movementIntensity
        movementIntensity = when {
            smoothedAcceleration >= MOVEMENT_THRESHOLD_MOVING -> MovementIntensity.TRAVELING
            smoothedAcceleration >= MOVEMENT_THRESHOLD_WALKING -> MovementIntensity.MOVING
            smoothedAcceleration >= MOVEMENT_THRESHOLD_STATIONARY -> MovementIntensity.WALKING
            else -> MovementIntensity.STATIONARY
        }
        
        // Update movement state
        val previousMovingState = isMoving
        isMoving = movementIntensity != MovementIntensity.STATIONARY
        
        // Notify if state changed
        if (previousMovingState != isMoving || previousIntensity != movementIntensity) {
            Log.d(TAG, "Movement state changed: ${movementIntensity.name}, " +
                      "Acceleration: ${String.format("%.2f", smoothedAcceleration)} m/s²")
            onMovementStateChanged?.invoke(isMoving, movementIntensity)
        }
        
        // Update last values
        last_x = x
        last_y = y
        last_z = z
        lastUpdate = currentTime
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not needed for accelerometer
    }
    
    /**
     * Get human-readable movement description
     */
    fun getMovementDescription(): String {
        return when (movementIntensity) {
            MovementIntensity.STATIONARY -> "Stationary"
            MovementIntensity.WALKING -> "Walking"
            MovementIntensity.MOVING -> "Moving"
            MovementIntensity.TRAVELING -> "Traveling (Vehicle)"
        }
    }
    
    /**
     * Check if user is likely in a vehicle
     */
    fun isLikelyInVehicle(): Boolean {
        return movementIntensity == MovementIntensity.TRAVELING
    }
    
    /**
     * Get trust score modifier based on movement
     * Returns 0 if stationary, positive bonus if moving (reduces penalties)
     */
    fun getTrustScoreModifier(): Int {
        return when (movementIntensity) {
            MovementIntensity.STATIONARY -> 0
            MovementIntensity.WALKING -> 5  // +5 points (minor movement)
            MovementIntensity.MOVING -> 10  // +10 points (active movement)
            MovementIntensity.TRAVELING -> 15  // +15 points (traveling - expect new towers)
        }
    }
}
