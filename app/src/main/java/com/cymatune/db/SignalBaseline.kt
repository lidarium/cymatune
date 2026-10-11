package com.cymatune.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks signal baseline for frequently seen towers.
 * Used to detect anomalies (e.g., FBS mimicking legitimate towers).
 */
@Entity(tableName = "signal_baseline")
data class SignalBaseline(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // Tower identifier
    val cid: Int,
    val lac: Int,
    val mcc: Int,
    val mnc: Int,
    
    // Approximate location (for context)
    val latitude: Double,
    val longitude: Double,
    val locationRadius: Double = 100.0, // meters
    
    // Statistical baseline (running average)
    val avgRsrp: Double,
    val avgRsrq: Double?,
    val avgSinr: Double?,
    val avgNeighborCount: Int,
    
    // Variance for anomaly detection
    val stdDevRsrp: Double,
    val stdDevNeighborCount: Double,
    
    // Sample metadata
    val sampleCount: Int = 1,
    val firstSeen: Long,
    val lastSeen: Long,
    
    // Time-of-day context (0-23)
    val primaryHour: Int? = null // Hour when most frequently seen
)
