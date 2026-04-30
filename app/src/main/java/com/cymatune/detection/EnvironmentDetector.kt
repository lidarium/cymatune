package com.cymatune.detection

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.util.Log
import kotlin.math.abs

/**
 * EnvironmentDetector - Determines if the user is indoors or outdoors
 * 
 * Uses multiple signals to estimate environment:
 * - GPS accuracy and satellite count
 * - Light sensor readings
 * - Barometric pressure stability
 * - Signal characteristics
 * 
 * This information is used to:
 * - Adjust path-loss model parameters
 * - Reduce false positives from indoor GPS drift
 * - Improve tower location estimation
 */
class EnvironmentDetector(private val context: Context) : SensorEventListener {
    private val TAG = "EnvironmentDetector"
    
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var lightSensor: Sensor? = null
    private var pressureSensor: Sensor? = null
    
    // Current measurements
    private var currentLightLevel: Float = -1f
    private var pressureHistory = mutableListOf<Pair<Long, Float>>()
    private var lastGpsAccuracy: Float = 1000f
    private var lastSatelliteCount: Int = 0
    private var wifiNetworkCount: Int = 0
    
    // Detection state
    private var currentEnvironment: Environment = Environment.UNKNOWN
    private var lastUpdateTime: Long = 0
    
    // Callbacks
    var onEnvironmentChanged: ((Environment) -> Unit)? = null
    
    init {
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        pressureSensor = sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
        
        Log.i(TAG, "EnvironmentDetector initialized - Light sensor: ${lightSensor != null}, Pressure: ${pressureSensor != null}")
    }
    
    /**
     * Start monitoring environment
     */
    fun start() {
        lightSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        pressureSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        Log.d(TAG, "Environment monitoring started")
    }
    
    /**
     * Stop monitoring
     */
    fun stop() {
        sensorManager.unregisterListener(this)
        Log.d(TAG, "Environment monitoring stopped")
    }
    
    /**
     * Update with current GPS data
     */
    fun updateGpsData(location: Location, satelliteCount: Int) {
        lastGpsAccuracy = location.accuracy
        lastSatelliteCount = satelliteCount
        updateEnvironmentEstimate()
    }
    
    /**
     * Update with WiFi network count
     */
    fun updateWifiCount(count: Int) {
        wifiNetworkCount = count
    }
    
    /**
     * Get current environment estimate
     */
    fun getCurrentEnvironment(): Environment = currentEnvironment
    
    /**
     * Get path loss exponent adjustment based on environment
     * Indoor environments typically have higher path loss (n = 3-6)
     * Outdoor environments have lower path loss (n = 2-3)
     */
    fun getPathLossExponent(): Double {
        return when (currentEnvironment) {
            Environment.OUTDOOR_OPEN -> 2.2     // Free space approximation
            Environment.OUTDOOR_URBAN -> 2.8   // Urban outdoor
            Environment.INDOOR_LIGHT -> 3.5    // Light indoor (near windows)
            Environment.INDOOR_DEEP -> 4.5     // Deep indoor
            Environment.UNKNOWN -> 3.0         // Default for mixed/unknown
        }
    }
    
    /**
     * Get confidence multiplier for GPS-based speed calculations
     * Lower confidence in indoor/poor GPS conditions
     */
    fun getGpsConfidenceMultiplier(): Double {
        return when {
            lastGpsAccuracy < 10 && lastSatelliteCount > 8 -> 1.0   // Excellent GPS
            lastGpsAccuracy < 30 && lastSatelliteCount > 5 -> 0.8   // Good GPS
            lastGpsAccuracy < 50 -> 0.5                              // Moderate GPS
            else -> 0.2                                               // Poor GPS
        }
    }
    
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                currentLightLevel = event.values[0]
            }
            Sensor.TYPE_PRESSURE -> {
                val now = System.currentTimeMillis()
                pressureHistory.add(now to event.values[0])
                // Keep last 5 minutes of pressure data
                pressureHistory.removeAll { it.first < now - 300_000 }
            }
        }
        
        updateEnvironmentEstimate()
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Not used
    }
    
    private fun updateEnvironmentEstimate() {
        val now = System.currentTimeMillis()
        // Only update every 5 seconds to avoid rapid changes
        if (now - lastUpdateTime < 5000) return
        lastUpdateTime = now
        
        // Calculate individual scores
        val gpsScore = calculateGpsScore()
        val lightScore = calculateLightScore()
        val pressureScore = calculatePressureScore()
        val wifiScore = calculateWifiScore()
        
        // Weighted combination
        val outdoorScore = (gpsScore * 0.4) + (lightScore * 0.3) + (pressureScore * 0.1) + (wifiScore * 0.2)
        
        val newEnvironment = when {
            outdoorScore > 0.8 -> Environment.OUTDOOR_OPEN
            outdoorScore > 0.6 -> Environment.OUTDOOR_URBAN
            outdoorScore > 0.4 -> Environment.INDOOR_LIGHT
            outdoorScore > 0.2 -> Environment.INDOOR_DEEP
            else -> Environment.UNKNOWN
        }
        
        if (newEnvironment != currentEnvironment) {
            Log.i(TAG, "Environment changed: $currentEnvironment -> $newEnvironment (score: ${String.format("%.2f", outdoorScore)})")
            currentEnvironment = newEnvironment
            onEnvironmentChanged?.invoke(newEnvironment)
        }
    }
    
    /**
     * GPS score: 1.0 = definitely outdoor, 0.0 = definitely indoor
     */
    private fun calculateGpsScore(): Double {
        return when {
            lastGpsAccuracy < 10 && lastSatelliteCount > 10 -> 1.0  // Clear sky
            lastGpsAccuracy < 15 && lastSatelliteCount > 8 -> 0.9   // Good outdoor
            lastGpsAccuracy < 30 && lastSatelliteCount > 5 -> 0.6   // Urban outdoor
            lastGpsAccuracy < 50 && lastSatelliteCount > 3 -> 0.3   // Near windows
            else -> 0.1                                              // Deep indoor
        }
    }
    
    /**
     * Light score: 1.0 = bright outdoor, 0.0 = dark indoor
     */
    private fun calculateLightScore(): Double {
        return when {
            currentLightLevel < 0 -> 0.5          // Unknown
            currentLightLevel > 10000 -> 1.0      // Direct sunlight
            currentLightLevel > 5000 -> 0.8       // Bright outdoor/shade
            currentLightLevel > 1000 -> 0.6       // Overcast/indoor bright
            currentLightLevel > 200 -> 0.3        // Indoor normal
            else -> 0.1                           // Dark / night
        }
    }
    
    /**
     * Pressure score: outdoor = more stable, indoor = more variable (HVAC)
     * This is a simplified heuristic
     */
    private fun calculatePressureScore(): Double {
        if (pressureHistory.size < 3) return 0.5
        
        val pressures = pressureHistory.map { it.second }
        val variance = pressures.map { p -> (p - pressures.average()).let { it * it } }.average()
        
        // Low variance = more stable = likely outdoor
        return when {
            variance < 0.5 -> 0.7    // Very stable - outdoor
            variance < 2.0 -> 0.5    // Normal variation
            else -> 0.3              // High variation - HVAC affected
        }
    }
    
    /**
     * WiFi score: many networks = likely indoor, few = outdoor
     */
    private fun calculateWifiScore(): Double {
        return when {
            wifiNetworkCount > 20 -> 0.2    // Many networks - office/mall
            wifiNetworkCount > 10 -> 0.4    // Several networks - indoor
            wifiNetworkCount > 3 -> 0.6     // Few networks - near buildings
            else -> 0.8                      // Very few - outdoor
        }
    }
    
    enum class Environment {
        OUTDOOR_OPEN,      // Open sky, clear GPS
        OUTDOOR_URBAN,     // Urban canyon, moderate GPS
        INDOOR_LIGHT,      // Near windows, some GPS
        INDOOR_DEEP,       // Deep indoor, poor/no GPS
        UNKNOWN            // Cannot determine
    }
}
