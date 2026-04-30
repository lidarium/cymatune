package com.cymatune.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Index

/**
 * LAC/CID Pattern Tracking Entity
 * 
 * Tracks patterns of LAC/CID combinations to detect anomalies such as:
 * - Outlier LAC/CID patterns in geographic areas
 * - Sudden LAC changes in small geographic areas (potential IMSI catchers)
 * - Unusual CID distributions within LACs
 * 
 * This entity enables detection of fake towers by analyzing:
 * 1. Geographic consistency of LAC/CID patterns
 * 2. Historical patterns of tower behavior
 * 3. Anomalous LAC/CID combinations
 */
@Entity(
    tableName = "lac_cid_patterns",
    indices = [
        Index(value = ["mcc", "mnc", "lac"]),
        Index(value = ["mcc", "mnc", "cid"]),
        Index(value = ["mcc", "mnc", "lac", "cid"]),
        Index(value = ["latitude", "longitude"]),
        Index(value = ["patternType"])
    ]
)
data class LacCidPattern(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    
    // Tower identification
    val cid: Int,
    val lac: Int,
    val mcc: Int,
    val mnc: Int,
    
    // Geographic context
    val latitude: Double,
    val longitude: Double,
    val locationRadius: Double = 500.0, // meters - typical cell radius
    
    // Pattern type for different anomaly detection
    val patternType: PatternType, // Geographic, Temporal, Statistical
    
    // Pattern characteristics
    val patternCount: Int = 1, // How many times this pattern has been observed
    val firstSeen: Long,
    val lastSeen: Long,
    
    // Geographic validation metrics
    val avgDistanceFromCenter: Double = 0.0, // Average distance from pattern center
    val maxDistanceFromCenter: Double = 0.0, // Maximum distance from pattern center
    val minDistanceFromCenter: Double = Double.MAX_VALUE, // Minimum distance from pattern center
    
    // Statistical metrics for anomaly detection
    val confidenceScore: Double = 100.0, // Higher = more trustworthy (0-100)
    val anomalyScore: Double = 0.0, // Higher = more suspicious (0-100)
    
    // Contextual information
    val networkType: String? = null, // LTE, 5G, etc.
    val cellType: CellType = CellType.UNKNOWN, // Macro, Micro, Pico, Indoor
    
    // Detection flags
    val isSuspicious: Boolean = false,
    val detectionReasons: String = "", // Comma-separated list of reasons
    
    // Geographic clustering
    val clusterId: String? = null, // ID for geographic cluster
    val clusterSize: Int = 1 // Number of towers in this cluster
)

/**
 * Types of LAC/CID patterns for different detection strategies
 */
enum class PatternType {
    /**
     * Geographic patterns - towers seen in specific geographic areas
     */
    GEOGRAPHIC,
    
    /**
     * Temporal patterns - towers seen at specific times
     */
    TEMPORAL,
    
    /**
     * Statistical patterns - towers with unusual signal/behavior characteristics
     */
    STATISTICAL,
    
    /**
     * Suspicious patterns - towers already flagged as suspicious
     */
    SUSPICIOUS
}

/**
 * Cell types for different deployment scenarios
 */
enum class CellType {
    UNKNOWN,
    MACRO,      // Large coverage area towers
    MICRO,      // Medium coverage area
    PICO,       // Small coverage area
    FEMTO,      // Very small coverage area (indoor)
    INDOOR      // Indoor distributed antenna systems
}

/**
 * Geographic cluster information for grouping nearby towers
 */
data class GeographicCluster(
    val clusterId: String,
    val centerLat: Double,
    val centerLon: Double,
    val radius: Double,
    val towerCount: Int,
    val lacs: Set<Int>,
    val cids: Set<Int>,
    val mcc: Int,
    val mnc: Int
)

/**
 * Types of LAC/CID anomalies
 */
enum class AnomalyType {
    UNUSUAL_GEOGRAPHIC_LOCATION,    // Tower in unexpected location
    SUDDEN_LAC_CHANGE,             // Rapid LAC changes in small area
    OUTLIER_CID,                   // CID not typically seen in this area
    UNUSUAL_SIGNAL_PATTERN,        // Signal strength doesn't match expected
    TEMPORAL_ANOMALY,              // Tower appears at unusual times
    CLUSTER_INCONSISTENCY,         // Tower doesn't fit expected cluster pattern
    FREQUENCY_ANOMALY              // Tower on unexpected frequency
}

/**
 * Anomaly detection result for LAC/CID patterns
 */
data class LacCidAnomalyResult(
    val isAnomalous: Boolean,
    val anomalyScore: Double, // 0-100 scale
    val confidenceScore: Double, // 0-100 scale
    val anomalyTypes: List<AnomalyType>,
    val detectionReasons: List<String>,
    val suggestedActions: List<String>
)