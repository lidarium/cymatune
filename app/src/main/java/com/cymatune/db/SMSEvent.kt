package com.cymatune.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import androidx.room.TypeConverters
import com.cymatune.util.ThreatLevel

/**
 * SMS Event entity for tracking and analyzing SMS messages
 * that may be related to fake tower detection.
 */
@Entity(
    tableName = "sms_events",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["cid", "lac"]),
        Index(value = ["threatLevel"])
    ]
)
data class SMSEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // Message identification
    val timestamp: Long,
    val senderNumber: String,
    val sender: String? = null,             // Alias for senderNumber
    val messageBody: String = "",
    
    // Network context
    val cid: Int = 0,      // Cell ID
    val lac: Int = 0,      // Location Area Code
    val mcc: Int = 0,      // Mobile Country Code
    val mnc: Int = 0,      // Mobile Network Code
    val signalStrength: Int = 0,
    val networkType: String = "UNKNOWN",
    val latitude: Double? = null,           // GPS coordinates if available
    val longitude: Double? = null,
    val timingAdvance: Int? = null,         // Timing advance for distance estimation
    
    // Analysis results
    @TypeConverters(ThreatLevelTypeConverter::class)
    val threatLevel: ThreatLevel = ThreatLevel.LOW,
    val threatConfidence: Double = 0.0,    // Confidence in threat assessment
    val isSuspicious: Boolean = false,
    val correlationScore: Double = 0.0,
    val analysisResult: String? = null,     // Detailed analysis result
    val contentAnalysis: String? = null,    // Content analysis result
    
    // Message metadata
    val messageType: String = "UNKNOWN",       // INCOMING, OUTGOING, MWI, EMERGENCY
    val messageFormat: String = "UNKNOWN",     // TEXT, PDU, WAP_PUSH
    val encoding: String = "UNKNOWN",          // GSM, UNICODE, 8BIT
    val messageSize: Int = 0,
    val isSilent: Boolean = false,          // Whether this is a silent SMS
    
    // Processing flags
    val isAnalyzed: Boolean = false,
    val analysisTimestamp: Long = 0,
    val analysisVersion: String = "1.0"
)