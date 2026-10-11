package com.cymatune.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * Paging Event Data Entity
 * 
 * Tracks cellular network paging requests and connection events
 * for detecting paging storms and potential IMSI catchers.
 * Paging storms are a common technique used by fake base stations
 * to force devices to reveal their identity.
 */
@Entity(
    tableName = "paging_events",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["cid", "lac", "mcc", "mnc"]),
        Index(value = ["eventType"]),
        Index(value = ["isPartOfStorm"]),
        Index(value = ["stormId"]),
        Index(value = ["timestamp", "cid"]),
        Index(value = ["networkType"]),
        Index(value = ["timingAdvance"])
    ]
)
data class PagingEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    
    // Event metadata
    val timestamp: Long,
    
    // Tower identification
    val cid: Int,
    val lac: Int,
    val mcc: Int,
    val mnc: Int,
    
    // Event classification
    val eventType: PagingEventType = PagingEventType.UNKNOWN,
    
    // Signal characteristics
    val signalStrength: Int,
    val timingAdvance: Int = 0,
    
    // Network context
    val networkType: String, // LTE, 5G, UMTS, GSM, etc.
    
    // Storm correlation
    val isPartOfStorm: Boolean = false,
    val stormId: Long? = null, // Reference to paging_storms table
    
    // Geographic context
    val latitude: Double? = null,
    val longitude: Double? = null,
    
    // Analysis results
    val anomalyScore: Double = 0.0, // 0-100 scale for suspiciousness
    val confidence: Double = 0.0,   // Confidence in classification
    val analysisMetadata: String? = null // JSON string for additional analysis data
    
    // Note: Unlike SMS events, paging events are typically detected
    // through network state monitoring and signal pattern analysis
    // rather than direct API access, as Android doesn't expose
    // direct paging request APIs to applications.
)

/**
 * Paging Event Types for classification
 */
enum class PagingEventType {
    REGISTRATION_REQUEST,     // Device registration with network
    LOCATION_UPDATE,          // Periodic location update
    PAGING_RESPONSE,          // Response to network page
    AUTHENTICATION_REQUEST,   // Network authentication challenge
    SERVICE_REQUEST,          // General service request
    UNKNOWN                   // Unable to classify
}