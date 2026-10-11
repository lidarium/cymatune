package com.cymatune.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "neighbor_history")
data class NeighborHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val primaryCid: Int,
    val primaryLac: Int,
    val primaryMcc: Int,
    val primaryMnc: Int,
    val neighborCids: String, // Comma-separated list of neighbor CIDs
    val neighborCount: Int,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long,
    val firstSeen: Long = System.currentTimeMillis() // Added for Long-Term Validation
)
