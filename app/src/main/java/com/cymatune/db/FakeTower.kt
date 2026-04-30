package com.cymatune.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "FakeTower")
data class FakeTower(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cid: Int,
    val lac: Int,
    val mcc: Int = 0, // 0 = unknown (neighbor cells)
    val mnc: Int = 0, // 0 = unknown (neighbor cells)
    val latitude: Double,
    val longitude: Double,
    val detectionTime: Long,
    val accuracy: Double,
    val reason: String,
    val signalStrength: Int,
    val networkType: String,
    val observationCount: Int = 1, // Number of readings used for location estimation
    val precisionRadius: Double = 1000.0, // Estimated radius in meters (starts at 1km)
    val isTriangulated: Boolean = false, // True if tower location has been triangulated, false if estimated
    val encryptionProtocol: String? = null, // Encryption protocol used (GSM A5/x, LTE EEA, etc.)
    val cipherStrength: Int = 0, // Cipher strength score (0-100)
    val authenticationMethod: String? = null, // Authentication method used (AKA, EAP-AKA, etc.)
    val securityCapabilities: String? = null, // Security capabilities as comma-separated string
    val trustScore: Int = 100, // Trust Score (0-100)
    val pci: Int? = null, // Physical Cell ID
    val arfcn: Int? = null, // Absolute Radio Frequency Channel Number
    val timingAdvance: Int? = null, // Timing Advance
    val rsrq: Int? = null, // Reference Signal Received Quality
    val sinr: Int? = null, // Signal to Interference plus Noise Ratio
    val hasHistoricThreat: Boolean = false, // Persistent threat flag that remains even if current scan is clean
    val lastThreatTimestamp: Long? = null // Timestamp of the last detected threat
)