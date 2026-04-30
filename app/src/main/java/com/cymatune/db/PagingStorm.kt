package com.cymatune.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index
import androidx.room.TypeConverters
import com.cymatune.util.ThreatLevel

/**
 * Paging Storm Data Entity
 * 
 * Represents a detected paging storm - a series of rapid paging requests
 * that may indicate IMSI catcher activity or other network-based attacks.
 * Paging storms are a common technique used by fake base stations to
 * force mobile devices to reveal their IMSI (International Mobile
 * Subscriber Identity).
 */
@Entity(
    tableName = "paging_storms",
    indices = [
        Index(value = ["startTime"]),
        Index(value = ["endTime"]),
        Index(value = ["affectedCid", "affectedLac", "affectedMcc", "affectedMnc"]),
        Index(value = ["threatLevel"]),
        Index(value = ["confidence"]),
        Index(value = ["detectedPatterns"]),
        Index(value = ["resolved"])
    ]
)
data class PagingStorm(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    
    // Storm timeline
    val startTime: Long,
    val endTime: Long? = null, // Null if storm is still active
    
    // Event statistics
    val eventCount: Int,
    val durationSeconds: Int = 0, // Calculated duration in seconds
    
    // Geographic scope
    val affectedCid: Int,
    val affectedLac: Int,
    val affectedMcc: Int,
    val affectedMnc: Int,
    
    // Geographic context
    val stormLatitude: Double? = null,
    val stormLongitude: Double? = null,
    val stormRadius: Double = 0.0, // Estimated radius of storm impact in meters
    
    // Threat assessment
    @TypeConverters(ThreatLevelTypeConverter::class)
    val threatLevel: ThreatLevel = ThreatLevel.LOW,
    val confidence: Double = 0.0, // Confidence in storm detection (0.0-1.0)
    val anomalyScore: Double = 0.0, // Overall anomaly score (0-100)
    
    // Pattern analysis
    val detectedPatterns: String, // Comma-separated list of detected patterns
    val patternDetails: String? = null, // JSON string with detailed pattern analysis
    
    // Storm characteristics
    val avgIntervalMs: Double = 0.0, // Average interval between events
    val minIntervalMs: Long = Long.MAX_VALUE, // Minimum interval between events
    val maxIntervalMs: Long = 0L, // Maximum interval between events
    val eventRatePerMinute: Double = 0.0, // Events per minute
    
    // Classification and metadata
    val stormType: PagingStormType = PagingStormType.UNKNOWN,
    val classificationReason: String = "", // Why this was classified as a storm
    val analysisTimestamp: Long = 0L,
    
    // Resolution status
    val resolved: Boolean = false,
    val resolutionTimestamp: Long? = null,
    val resolutionReason: String? = null, // How/why the storm ended
    
    // Related data
    val relatedThreats: String? = null, // Comma-separated list of related threat IDs
    val forensicData: String? = null // JSON string with forensic analysis data
    
    // Note: A paging storm is typically defined as:
    // - Multiple paging requests to the same cell within a short time window
    // - Unusually high frequency of location updates or registration requests
    // - Patterns inconsistent with normal network behavior
)

/**
 * Types of Paging Storms for different attack classification
 */
enum class PagingStormType {
    UNKNOWN,                    // Unable to classify
    IMSI_CATCHER,              // Likely IMSI catcher activity
    LOCATION_TRACKING,         // Attempted location tracking
    NETWORK_SURVEILLANCE,      // General network surveillance
    AUTHENTICATION_STORM,      // Repeated authentication attempts
    SERVICE_OVERLOAD,          // Network service overload/failure
    TESTING_OR_MAINTENANCE,    // Network testing or maintenance
    MALWARE_INDUCED            // Malware-induced paging activity
}