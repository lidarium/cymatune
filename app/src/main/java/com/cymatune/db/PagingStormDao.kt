package com.cymatune.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import com.cymatune.util.ThreatLevel

/**
 * Data Access Object for Paging Storm tracking
 * 
 * Provides comprehensive data access operations for paging storms,
 * supporting detection, analysis, and correlation of IMSI catcher
 * activities and other network-based security threats.
 */
@Dao
interface PagingStormDao {

    //region Basic CRUD Operations
    
    /**
     * Insert a new paging storm or replace existing one
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPagingStorm(pagingStorm: PagingStorm): Long
    
    /**
     * Update an existing paging storm
     */
    @Update
    suspend fun updatePagingStorm(pagingStorm: PagingStorm)
    
    /**
     * Delete a paging storm by ID
     */
    @Query("DELETE FROM paging_storms WHERE id = :id")
    suspend fun deletePagingStorm(id: Long)
    
    //endregion
    
    //region Storm Retrieval and Analysis
    
    /**
     * Get all paging storms ordered by start time (newest first)
     */
    @Query("SELECT * FROM paging_storms ORDER BY startTime DESC")
    fun getAllPagingStorms(): Flow<List<PagingStorm>>
    
    /**
     * Get paging storms within a specific time range
     */
    @Query("SELECT * FROM paging_storms WHERE startTime BETWEEN :startTime AND :endTime ORDER BY startTime DESC")
    suspend fun getPagingStormsInTimeRange(startTime: Long, endTime: Long): List<PagingStorm>
    
    /**
     * Get recent paging storms (last N hours)
     */
    @Query("SELECT * FROM paging_storms WHERE startTime > :cutoffTime ORDER BY startTime DESC")
    suspend fun getRecentPagingStorms(cutoffTime: Long): List<PagingStorm>
    
    /**
     * Get active paging storms (storms that haven't ended yet)
     */
    @Query("SELECT * FROM paging_storms WHERE endTime IS NULL ORDER BY startTime DESC")
    suspend fun getActivePagingStorms(): List<PagingStorm>
    
    /**
     * Get resolved paging storms
     */
    @Query("SELECT * FROM paging_storms WHERE resolved = 1 ORDER BY endTime DESC")
    suspend fun getResolvedPagingStorms(): List<PagingStorm>
    
    //endregion
    
    //region Threat Assessment and Classification
    
    /**
     * Get paging storms by threat level
     */
    @Query("SELECT * FROM paging_storms WHERE threatLevel = :threatLevel ORDER BY startTime DESC")
    suspend fun getPagingStormsByThreatLevel(threatLevel: ThreatLevel): List<PagingStorm>
    
    /**
     * Get paging storms by storm type
     */
    @Query("SELECT * FROM paging_storms WHERE stormType = :stormType ORDER BY startTime DESC")
    suspend fun getPagingStormsByType(stormType: PagingStormType): List<PagingStorm>
    
    /**
     * Get high-confidence paging storms
     */
    @Query("SELECT * FROM paging_storms WHERE confidence > :minConfidence ORDER BY confidence DESC")
    suspend fun getHighConfidenceStorms(minConfidence: Double): List<PagingStorm>
    
    /**
     * Get paging storms with high anomaly scores
     */
    @Query("SELECT * FROM paging_storms WHERE anomalyScore > :threshold ORDER BY anomalyScore DESC")
    suspend fun getHighAnomalyStorms(threshold: Double): List<PagingStorm>
    
    //endregion
    
    //region Geographic and Tower Analysis
    
    /**
     * Get paging storms for a specific tower (MCC-MNC-LAC-CID)
     */
    @Query("SELECT * FROM paging_storms WHERE affectedMcc = :mcc AND affectedMnc = :mnc AND affectedLac = :lac AND affectedCid = :cid ORDER BY startTime DESC")
    suspend fun getPagingStormsForTower(mcc: Int, mnc: Int, lac: Int, cid: Int): List<PagingStorm>
    
    /**
     * Get paging storms for a specific network (MCC-MNC)
     */
    @Query("SELECT * FROM paging_storms WHERE affectedMcc = :mcc AND affectedMnc = :mnc ORDER BY startTime DESC")
    suspend fun getPagingStormsForNetwork(mcc: Int, mnc: Int): List<PagingStorm>
    
    /**
     * Get paging storms within a geographic radius
     */
    @Query("""
        SELECT * FROM paging_storms 
        WHERE stormLatitude IS NOT NULL AND stormLongitude IS NOT NULL
        AND (
            (stormLatitude - :centerLat) * (stormLatitude - :centerLat) + 
            (stormLongitude - :centerLon) * (stormLongitude - :centerLon)
        ) <= (:radius * :radius / 12742000.0)
        ORDER BY startTime DESC
    """)
    suspend fun getPagingStormsInRadius(
        centerLat: Double, 
        centerLon: Double, 
        radius: Double
    ): List<PagingStorm>
    
    /**
     * Get towers affected by paging storms
     */
    @Query("""
        SELECT DISTINCT 
            affectedCid, affectedLac, affectedMcc, affectedMnc,
            COUNT(*) as stormCount,
            MAX(startTime) as lastStorm,
            AVG(anomalyScore) as avgAnomaly
        FROM paging_storms 
        WHERE startTime > :sinceTime
        GROUP BY affectedCid, affectedLac, affectedMcc, affectedMnc
        ORDER BY stormCount DESC, avgAnomaly DESC
    """)
    suspend fun getAffectedTowers(sinceTime: Long): List<AffectedTowerAnalysis>
    
    //endregion
    
    //region Temporal and Pattern Analysis
    
    /**
     * Get paging storms by duration range
     */
    @Query("SELECT * FROM paging_storms WHERE durationSeconds BETWEEN :min AND :max ORDER BY durationSeconds DESC")
    suspend fun getPagingStormsByDuration(min: Int, max: Int): List<PagingStorm>
    
    /**
     * Get paging storms by event rate
     */
    @Query("SELECT * FROM paging_storms WHERE eventRatePerMinute > :minRate ORDER BY eventRatePerMinute DESC")
    suspend fun getPagingStormsByEventRate(minRate: Double): List<PagingStorm>
    
    /**
     * Get paging storms with specific detected patterns
     */
    @Query("SELECT * FROM paging_storms WHERE detectedPatterns LIKE :pattern ORDER BY startTime DESC")
    suspend fun getPagingStormsByPattern(pattern: String): List<PagingStorm>
    
    /**
     * Get paging storm frequency by time period
     */
    @Query("""
        SELECT 
            strftime('%Y-%m-%d %H', datetime(startTime/1000, 'unixepoch')) as hour,
            COUNT(*) as stormCount,
            SUM(eventCount) as totalEvents,
            AVG(anomalyScore) as avgAnomaly,
            MAX(CASE WHEN threatLevel = 'HIGH' OR threatLevel = 'CRITICAL' THEN 1 ELSE 0 END) as hasHighThreat
        FROM paging_storms 
        WHERE startTime > :sinceTime
        GROUP BY hour
        ORDER BY hour DESC
    """)
    suspend fun getPagingStormFrequency(sinceTime: Long): List<StormFrequencyAnalysis>
    
    //endregion
    
    //region Statistical and Aggregation Queries
    
    /**
     * Count paging storms by type
     */
    @Query("SELECT stormType, COUNT(*) as count FROM paging_storms GROUP BY stormType ORDER BY count DESC")
    suspend fun countPagingStormsByType(): List<StormTypeCount>
    
    /**
     * Count paging storms by threat level
     */
    @Query("SELECT threatLevel, COUNT(*) as count FROM paging_storms GROUP BY threatLevel ORDER BY threatLevel")
    suspend fun countPagingStormsByThreatLevel(): List<ThreatLevelCount>
    
    /**
     * Get paging storm statistics for dashboard
     */
    @Query("""
        SELECT 
            COUNT(*) as totalStorms,
            SUM(CASE WHEN resolved = 0 THEN 1 ELSE 0 END) as activeStorms,
            SUM(CASE WHEN threatLevel = 'HIGH' OR threatLevel = 'CRITICAL' THEN 1 ELSE 0 END) as highThreatStorms,
            AVG(eventCount) as avgEventsPerStorm,
            AVG(anomalyScore) as avgAnomalyScore,
            MAX(startTime) as latestStorm,
            COUNT(DISTINCT affectedCid) as affectedTowers
        FROM paging_storms
        WHERE startTime > :sinceTime
    """)
    suspend fun getPagingStormStatistics(sinceTime: Long): PagingStormStatistics?
    
    /**
     * Get storm resolution analysis
     */
    @Query("""
        SELECT 
            resolutionReason,
            COUNT(*) as count,
            AVG((endTime - startTime) / 1000) as avgDurationSeconds
        FROM paging_storms 
        WHERE resolved = 1 AND resolutionReason IS NOT NULL
        GROUP BY resolutionReason
        ORDER BY count DESC
    """)
    suspend fun getStormResolutionAnalysis(): List<StormResolutionAnalysis>
    
    //endregion
    
    //region Update Operations
    
    /**
     * Mark a paging storm as resolved
     */
    @Query("UPDATE paging_storms SET resolved = 1, endTime = :endTime, resolutionTimestamp = :resolutionTime, resolutionReason = :reason WHERE id = :id")
    suspend fun resolvePagingStorm(
        id: Long,
        endTime: Long,
        resolutionTime: Long,
        reason: String?
    )
    
    /**
     * Update storm analysis results
     */
    @Query("""
        UPDATE paging_storms 
        SET 
            threatLevel = :threatLevel,
            confidence = :confidence,
            anomalyScore = :anomalyScore,
            classificationReason = :reason,
            analysisTimestamp = :timestamp,
            patternDetails = :details
        WHERE id = :id
    """)
    suspend fun updateStormAnalysis(
        id: Long,
        threatLevel: ThreatLevel,
        confidence: Double,
        anomalyScore: Double,
        reason: String,
        timestamp: Long,
        details: String?
    )
    
    /**
     * Update storm geographic information
     */
    @Query("""
        UPDATE paging_storms 
        SET 
            stormLatitude = :latitude,
            stormLongitude = :longitude,
            stormRadius = :radius
        WHERE id = :id
    """)
    suspend fun updateStormGeographicInfo(
        id: Long,
        latitude: Double?,
        longitude: Double?,
        radius: Double
    )
    
    //endregion
    
    //region Cleanup and Maintenance
    
    /**
     * Delete old paging storms (for data retention)
     */
    @Query("DELETE FROM paging_storms WHERE startTime < :cutoffTime")
    suspend fun deleteOldPagingStorms(cutoffTime: Long)
    
    /**
     * Get unresolved storms older than threshold (for cleanup)
     */
    @Query("SELECT * FROM paging_storms WHERE resolved = 0 AND startTime < :cutoffTime ORDER BY startTime ASC")
    suspend fun getStaleUnresolvedStorms(cutoffTime: Long): List<PagingStorm>
    
    /**
     * Clean up forensic data for privacy (optional)
     */
    @Query("UPDATE paging_storms SET forensicData = NULL WHERE startTime < :cutoffTime")
    suspend fun cleanupForensicData(cutoffTime: Long)
    
    //endregion
}

// Result classes for complex queries
data class AffectedTowerAnalysis(
    val affectedCid: Int,
    val affectedLac: Int,
    val affectedMcc: Int,
    val affectedMnc: Int,
    val stormCount: Int,
    val lastStorm: Long,
    val avgAnomaly: Double
)

data class StormFrequencyAnalysis(
    val hour: String,
    val stormCount: Int,
    val totalEvents: Int,
    val avgAnomaly: Double,
    val hasHighThreat: Int
)

data class StormTypeCount(
    val stormType: PagingStormType,
    val count: Int
)

data class ThreatLevelCount(
    val threatLevel: ThreatLevel,
    val count: Int
)

data class StormResolutionAnalysis(
    val resolutionReason: String?,
    val count: Int,
    val avgDurationSeconds: Double
)

data class PagingStormStatistics(
    val totalStorms: Int,
    val activeStorms: Int,
    val highThreatStorms: Int,
    val avgEventsPerStorm: Double,
    val avgAnomalyScore: Double,
    val latestStorm: Long?,
    val affectedTowers: Int
)