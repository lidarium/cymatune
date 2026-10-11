package com.cymatune.util

import android.os.Parcelable
import androidx.room.*
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue

// Type Converters for Room Database
class Converters {
    // Simplified converters using string serialization

    @TypeConverter
    fun fromLocationTriples(value: MutableList<Triple<Double, Double, Long>>?): String? {
        return value?.joinToString(";") { "${it.first},${it.second},${it.third}" }
    }

    @TypeConverter
    fun toLocationTriples(value: String?): MutableList<Triple<Double, Double, Long>> {
        return if (value == null || value.isEmpty()) mutableListOf()
        else value.split(";").mapNotNull {
            val parts = it.split(",")
            if (parts.size == 3) Triple(parts[0].toDouble(), parts[1].toDouble(), parts[2].toLong())
            else null
        }.toMutableList()
    }

    @TypeConverter
    fun fromSignalStrengthPairs(value: MutableList<Pair<Long, Int>>?): String? {
        return value?.joinToString(";") { "${it.first},${it.second}" }
    }

    @TypeConverter
    fun toSignalStrengthPairs(value: String?): MutableList<Pair<Long, Int>> {
        return if (value == null || value.isEmpty()) mutableListOf()
        else value.split(";").mapNotNull {
            val parts = it.split(",")
            if (parts.size == 2) Pair(parts[0].toLong(), parts[1].toInt())
            else null
        }.toMutableList()
    }

    @TypeConverter
    fun fromAccelerometerDataList(value: MutableList<AccelerometerData>?): String? {
        return value?.joinToString(";") { "${it.x},${it.y},${it.z},${it.timestamp}" }
    }

    @TypeConverter
    fun toAccelerometerDataList(value: String?): MutableList<AccelerometerData> {
        return if (value == null || value.isEmpty()) mutableListOf()
        else value.split(";").mapNotNull {
            val parts = it.split(",")
            if (parts.size == 4) AccelerometerData(parts[0].toFloat(), parts[1].toFloat(), parts[2].toFloat(), parts[3].toLong())
            else null
        }.toMutableList()
    }

    @TypeConverter
    fun fromConnectionHistory(value: MutableList<Pair<Long, Boolean>>?): String? {
        return value?.joinToString(";") { "${it.first},${it.second}" }
    }

    @TypeConverter
    fun toConnectionHistory(value: String?): MutableList<Pair<Long, Boolean>> {
        return if (value == null || value.isEmpty()) mutableListOf()
        else value.split(";").mapNotNull {
            val parts = it.split(",")
            if (parts.size == 2) Pair(parts[0].toLong(), parts[1].toBoolean())
            else null
        }.toMutableList()
    }

    @TypeConverter
    fun fromDoublePair(value: Pair<Double, Double>?): String? {
        return value?.let { "${it.first},${it.second}" }
    }

    @TypeConverter
    fun toDoublePair(value: String?): Pair<Double, Double>? {
        return if (value == null || value.isEmpty()) null
        else {
            val parts = value.split(",")
            if (parts.size == 2) Pair(parts[0].toDouble(), parts[1].toDouble())
            else null
        }
    }

    @TypeConverter
    fun fromLongList(value: MutableList<Long>?): String? {
        return value?.joinToString(",")
    }

    @TypeConverter
    fun toLongList(value: String?): MutableList<Long> {
        return if (value == null || value.isEmpty()) mutableListOf()
        else value.split(",").mapNotNull { it.toLongOrNull() }.toMutableList()
    }

    @TypeConverter
    fun fromStringList(value: MutableList<String>?): String? {
        return value?.joinToString(",")
    }

    @TypeConverter
    fun toStringList(value: String?): MutableList<String> {
        return if (value == null || value.isEmpty()) mutableListOf()
        else value.split(",").toMutableList()
    }
}

data class AccelerometerData(
    val x: Float,
    val y: Float,
    val z: Float,
    val timestamp: Long
)

@Entity(primaryKeys = ["cid", "lac", "mcc", "mnc"])
@TypeConverters(Converters::class)
data class TowerInfo(
    val cid: Int,
    val lac: Int,
    val mcc: Int,
    val mnc: Int,
    var firstSeen: Long,
    var lastSeen: Long,
    val locations: MutableList<Triple<Double, Double, Long>> = mutableListOf(), // Lat, Lon, Timestamp
    val signalStrengths: MutableList<Pair<Long, Int>> = mutableListOf(), // Timestamp and signal strength
    var lastConnectedTime: Long = 0L,
    var disconnectionCount: Int = 0,
    val disconnectionTimestamps: MutableList<Long> = mutableListOf(),
    val accelerometerReadings: MutableList<AccelerometerData> = mutableListOf(),
    var lastLocationTimestamp: Long = 0L, // This line remains the same
    val connectionHistory: MutableList<Pair<Long, Boolean>> = mutableListOf(),
    var estimatedTowerLocation: Pair<Double, Double>? = null,
    var suspicionReason: String? = null,
    var suspicionConfidence: Double? = null,
    var isLocked: Boolean = false,
    var detectionPatterns: MutableList<String> = mutableListOf(), // Individual detection patterns
    var suspicionLevel: String = "none", // none, low, medium, high, severe
    var firstSuspicionTime: Long? = null, // When suspicion was first detected
    var suspicionDuration: Long = 0L, // How long suspicion has been building
    val historicalTowerLocations: MutableList<Triple<Double, Double, Long>> = mutableListOf(), // Historical estimated tower locations (Lat, Lon, Timestamp)
    var lastTowerLocationUpdate: Long = 0L // Timestamp of last tower location estimation
) {
    // Required default constructor for Room
    constructor() : this(0, 0, 0, 0, 0L, 0L)

    // Memory optimization: Clean up old data to prevent unbounded growth
    fun cleanupOldData(maxItems: Int = 1000, maxAgeHours: Int = 24) {
        val currentTime = System.currentTimeMillis()
        val maxAgeMillis = maxAgeHours * 60 * 60 * 1000L
        
        // Clean up locations
        if (locations.size > maxItems) {
            locations.removeAll { it.third < currentTime - maxAgeMillis }
            if (locations.size > maxItems) {
                locations.subList(maxItems, locations.size).clear()
            }
        }
        
        // Clean up signal strengths
        if (signalStrengths.size > maxItems) {
            signalStrengths.removeAll { it.first < currentTime - maxAgeMillis }
            if (signalStrengths.size > maxItems) {
                signalStrengths.subList(maxItems, signalStrengths.size).clear()
            }
        }
        
        // Clean up disconnection timestamps
        if (disconnectionTimestamps.size > maxItems) {
            disconnectionTimestamps.removeAll { it < currentTime - maxAgeMillis }
            if (disconnectionTimestamps.size > maxItems) {
                disconnectionTimestamps.subList(maxItems, disconnectionTimestamps.size).clear()
            }
        }
        
        // Clean up accelerometer readings
        if (accelerometerReadings.size > maxItems) {
            accelerometerReadings.removeAll { it.timestamp < currentTime - maxAgeMillis }
            if (accelerometerReadings.size > maxItems) {
                accelerometerReadings.subList(maxItems, accelerometerReadings.size).clear()
            }
        }
        
        // Clean up connection history
        if (connectionHistory.size > maxItems) {
            connectionHistory.removeAll { it.first < currentTime - maxAgeMillis }
            if (connectionHistory.size > maxItems) {
                connectionHistory.subList(maxItems, connectionHistory.size).clear()
            }
        }
        
        // Clean up historical tower locations
        if (historicalTowerLocations.size > maxItems) {
            historicalTowerLocations.removeAll { it.third < currentTime - maxAgeMillis }
            if (historicalTowerLocations.size > maxItems) {
                historicalTowerLocations.subList(maxItems, historicalTowerLocations.size).clear()
            }
        }
        
        // Clean up detection patterns (no timestamp, just limit size)
        if (detectionPatterns.size > maxItems) {
            detectionPatterns.subList(maxItems, detectionPatterns.size).clear()
        }
    }
}

@Entity(tableName = "tower_observations")
@TypeConverters(Converters::class)
data class TowerObservation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cid: Int,
    val lac: Int,
    val mcc: Int,
    val mnc: Int,
    val signal: Int,
    val rsrp: Int, // Reference Signal Received Power
    val rsrq: Int?, // Reference Signal Received Quality
    val sinr: Int?, // Signal to Interference plus Noise Ratio
    val pci: Int?, // Physical Cell ID
    val ta: Int?,
    val arfcn: Int?, // Absolute Radio Frequency Channel Number
    val band: Int?, // Frequency band
    val ssRsrp: Int?, // SS Reference Signal Received Power (5G NR)
    val ssRsrq: Int?, // SS Reference Signal Received Quality (5G NR)
    val ssSinr: Int?, // SS Signal to Interference plus Noise Ratio (5G NR)
    val latitude: Double?,
    val longitude: Double?,
    val locationAccuracy: Float?, // New field for GPS accuracy (e.g., in meters)
    val timestamp: Long,
    val source: String // e.g., "suspicious", "normal"
)

data class TowerInfoWithDetails(
    @Embedded
    val towerInfo: TowerInfo,
    val latestTA: Int?,
    val latestSignal: Int?
)

@Parcelize
data class TowerConnectionInfo(
    val subscriptionId: Int,
    val slotIndex: Int,
    val mcc: Int?, // Nullable to preserve data fidelity for neighbor cells
    val mnc: Int?, // Nullable to preserve data fidelity for neighbor cells,
    val lac: Int,
    val cid: Int,
    val signalStrength: Int,
    val timingAdvance: Int?,
    val pci: Int?, // Physical Cell ID
    val arfcn: Int?, // Absolute Radio Frequency Channel Number
    val band: Int?, // Frequency band
    val ssRsrp: Int?, // SS Reference Signal Received Power (5G NR)
    val ssRsrq: Int?, // SS Reference Signal Received Quality (5G NR)
    val ssSinr: Int?, // SS Signal to Interference plus Noise Ratio (5G NR)
    val isRegistered: Boolean,
    val cellInfo: @RawValue Any? = null,
    val estimatedLocation: Pair<Double, Double>? = null,
    val networkType: String? = null,
    val additionalInfo: Map<String, String>? = null
) : Parcelable

// Protocol-level encryption handshake detection data structures
data class EncryptionProtocolInfo(
    val protocolType: String, // GSM, UMTS, LTE, NR
    val cipherAlgorithm: String, // A5/1, A5/3, EEA0, EEA1, EEA2, EEA3
    val integrityAlgorithm: String?, // EIA1, EIA2, EIA3 (for UMTS/LTE/NR)
    val keyLength: Int,
    val keyExchangeMethod: String, // AKA, EAP-AKA, 5G-AKA
    val authenticationStatus: AuthenticationStatus,
    val handshakeDuration: Long?, // ms
    val handshakeSuccess: Boolean,
    val securityCapabilities: List<String>,
    val timestamp: Long,
    // 2G downgrade detection fields
    val isDowngradeDetected: Boolean = false,
    val downgradeReason: String? = null,
    val previousNetworkType: String? = null,
    val downgradeConfidence: Double = 0.0
)

enum class AuthenticationStatus {
    NOT_ATTEMPTED, SUCCESSFUL, FAILED, COMPROMISED, WEAK_CIPHER
}
