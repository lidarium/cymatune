package com.cymatune.db

import androidx.room.TypeConverter
import com.cymatune.util.ThreatLevel

/**
 * Type converter for ThreatLevel enum to String and vice versa.
 * Required for Room database to properly store and retrieve enum values.
 */
class ThreatLevelTypeConverter {
    
    /**
     * Convert ThreatLevel enum to String for database storage
     */
    @TypeConverter
    fun fromThreatLevel(threatLevel: ThreatLevel?): String? {
        return threatLevel?.toStringValue()
    }
    
    /**
     * Convert String from database to ThreatLevel enum
     */
    @TypeConverter
    fun toThreatLevel(threatLevelString: String?): ThreatLevel {
        return threatLevelString?.toThreatLevel() ?: ThreatLevel.LOW
    }
}

/**
 * Extension function to convert ThreatLevel to string for database storage
 */
fun ThreatLevel.toStringValue(): String = when (this) {
    ThreatLevel.LOW -> "LOW"
    ThreatLevel.MEDIUM -> "MEDIUM"
    ThreatLevel.HIGH -> "HIGH"
    ThreatLevel.CRITICAL -> "CRITICAL"
}

/**
 * Extension function to convert String to ThreatLevel enum
 */
fun String.toThreatLevel(): ThreatLevel = when (this.uppercase()) {
    "LOW" -> ThreatLevel.LOW
    "MEDIUM" -> ThreatLevel.MEDIUM
    "HIGH" -> ThreatLevel.HIGH
    "CRITICAL" -> ThreatLevel.CRITICAL
    else -> ThreatLevel.LOW
}