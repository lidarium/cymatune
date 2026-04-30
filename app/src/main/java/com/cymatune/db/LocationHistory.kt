package com.cymatune.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks known "safe" cell tower locations that the user frequently visits.
 * Used to build a "Trust Score" by identifying Home/Work towers.
 */
@Entity(tableName = "location_history")
data class LocationHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // Cell tower identifier
    val cid: Int,
    val lac: Int,
    val mcc: Int,
    val mnc: Int,
    
    // Location where this tower was seen
    val latitude: Double,
    val longitude: Double,
    
    // Time statistics
    val firstSeen: Long,
    val lastSeen: Long,
    val totalConnections: Int = 1,
    val totalDurationMinutes: Long = 0, // Total time connected to this tower
    
    // Label (optional, user-defined or auto-inferred)
    val label: String? = null, // "Home", "Work", "Frequent", etc.
    
    // Trust modifier
    val trustBonus: Int = 0 // +20 for "Home", +10 for "Frequent", etc.
)
