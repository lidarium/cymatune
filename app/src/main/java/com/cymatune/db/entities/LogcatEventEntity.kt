package com.cymatune.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "logcat_events")
data class LogcatEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,      // Stores ThreatType.name
    val severity: String,  // Stores ThreatSeverity.name
    val message: String,
    val cid: Int?,
    val timestamp: Long,
    val confidence: Float
)
