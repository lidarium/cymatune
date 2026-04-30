package com.cymatune.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for LAC/CID Pattern tracking
 * 
 * Provides methods for storing, retrieving, and analyzing LAC/CID patterns
 * to detect fake towers through geographic and statistical analysis.
 */
@Dao
interface LacCidPatternDao {

    //region Basic CRUD Operations
    
    /**
     * Insert a new LAC/CID pattern or update existing one
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPattern(pattern: LacCidPattern): Long
    
    /**
     * Update an existing pattern
     */
    @Update
    suspend fun updatePattern(pattern: LacCidPattern)
    
    /**
     * Delete a pattern by ID
     */
    @Query("DELETE FROM lac_cid_patterns WHERE id = :id")
    suspend fun deletePattern(id: Long)
    
    //endregion
    
    //region Pattern Retrieval by Tower Identity
    
    /**
     * Get all patterns for a specific tower (MCC-MNC-LAC-CID)
     */
    @Query("SELECT * FROM lac_cid_patterns WHERE mcc = :mcc AND mnc = :mnc AND lac = :lac AND cid = :cid ORDER BY lastSeen DESC")
    suspend fun getPatternsForTower(mcc: Int, mnc: Int, lac: Int, cid: Int): List<LacCidPattern>
    
    /**
     * Get the most recent pattern for a specific tower
     */
    @Query("SELECT * FROM lac_cid_patterns WHERE mcc = :mcc AND mnc = :mnc AND lac = :lac AND cid = :cid ORDER BY lastSeen DESC LIMIT 1")
    suspend fun getLatestPatternForTower(mcc: Int, mnc: Int, lac: Int, cid: Int): LacCidPattern?
    
    /**
     * Get all patterns for a specific LAC within a network
     */
    @Query("SELECT * FROM lac_cid_patterns WHERE mcc = :mcc AND mnc = :mnc AND lac = :lac ORDER BY patternCount DESC")
    suspend fun getPatternsForLac(mcc: Int, mnc: Int, lac: Int): List<LacCidPattern>
    
    /**
     * Get all patterns for a specific CID within a network
     */
    @Query("SELECT * FROM lac_cid_patterns WHERE mcc = :mcc AND mnc = :mnc AND cid = :cid ORDER BY patternCount DESC")
    suspend fun getPatternsForCid(mcc: Int, mnc: Int, cid: Int): List<LacCidPattern>
    
    //endregion
    
    //region Geographic Analysis
    
    /**
     * Get patterns within a geographic radius
     */
    @Query("""
        SELECT * FROM lac_cid_patterns 
        WHERE (
            (latitude - :centerLat) * (latitude - :centerLat) + 
            (longitude - :centerLon) * (longitude - :centerLon)
        ) <= (:radius * :radius / 12742000.0)
        ORDER BY patternCount DESC
    """)
    suspend fun getPatternsInRadius(
        centerLat: Double, 
        centerLon: Double, 
        radius: Double
    ): List<LacCidPattern>
    
    /**
     * Get patterns within a bounding box
     */
    @Query("""
        SELECT * FROM lac_cid_patterns 
        WHERE latitude BETWEEN :minLat AND :maxLat 
        AND longitude BETWEEN :minLon AND :maxLon
        ORDER BY patternCount DESC
    """)
    suspend fun getPatternsInBoundingBox(
        minLat: Double, 
        maxLat: Double,
        minLon: Double, 
        maxLon: Double
    ): List<LacCidPattern>
    
    /**
     * Get suspicious patterns in a geographic area
     */
    @Query("""
        SELECT * FROM lac_cid_patterns 
        WHERE isSuspicious = 1
        AND (
            (latitude - :centerLat) * (latitude - :centerLat) + 
            (longitude - :centerLon) * (longitude - :centerLon)
        ) <= (:radius * :radius / 12742000.0)
        ORDER BY anomalyScore DESC
    """)
    suspend fun getSuspiciousPatternsInRadius(
        centerLat: Double, 
        centerLon: Double, 
        radius: Double
    ): List<LacCidPattern>
    
    //endregion
    
    //region Pattern Analysis and Statistics
    
    /**
     * Get patterns by type for different analysis strategies
     */
    @Query("SELECT * FROM lac_cid_patterns WHERE patternType = :patternType ORDER BY patternCount DESC")
    suspend fun getPatternsByType(patternType: PatternType): List<LacCidPattern>
    
    /**
     * Get patterns with high anomaly scores (potential fake towers)
     */
    @Query("SELECT * FROM lac_cid_patterns WHERE anomalyScore > :threshold ORDER BY anomalyScore DESC")
    suspend fun getHighAnomalyPatterns(threshold: Double): List<LacCidPattern>
    
    /**
     * Get patterns with low confidence scores
     */
    @Query("SELECT * FROM lac_cid_patterns WHERE confidenceScore < :threshold ORDER BY confidenceScore ASC")
    suspend fun getLowConfidencePatterns(threshold: Double): List<LacCidPattern>
    
    /**
     * Count patterns by LAC to identify LACs with unusual tower distributions
     */
    @Query("""
        SELECT lac, COUNT(*) as towerCount, AVG(patternCount) as avgObservations
        FROM lac_cid_patterns 
        WHERE mcc = :mcc AND mnc = :mnc
        GROUP BY lac 
        ORDER BY towerCount DESC
    """)
    suspend fun getLacTowerDistribution(mcc: Int, mnc: Int): List<LacDistributionResult>
    
    //endregion
    
    //region Temporal Analysis
    
    /**
     * Get patterns observed within a time range
     */
    @Query("SELECT * FROM lac_cid_patterns WHERE lastSeen BETWEEN :startTime AND :endTime ORDER BY lastSeen DESC")
    suspend fun getPatternsInTimeRange(startTime: Long, endTime: Long): List<LacCidPattern>
    
    /**
     * Get recently observed patterns (last N hours)
     */
    @Query("SELECT * FROM lac_cid_patterns WHERE lastSeen > :cutoffTime ORDER BY lastSeen DESC")
    suspend fun getRecentPatterns(cutoffTime: Long): List<LacCidPattern>
    
    //endregion
    
    //region Flow-based Queries for Live Updates
    
    /**
     * Get all patterns as a Flow for real-time updates
     */
    @Query("SELECT * FROM lac_cid_patterns ORDER BY lastSeen DESC")
    fun getAllPatternsFlow(): Flow<List<LacCidPattern>>
    
    /**
     * Get suspicious patterns as a Flow
     */
    @Query("SELECT * FROM lac_cid_patterns WHERE isSuspicious = 1 ORDER BY anomalyScore DESC")
    fun getSuspiciousPatternsFlow(): Flow<List<LacCidPattern>>
    
    /**
     * Get patterns by type as a Flow
     */
    @Query("SELECT * FROM lac_cid_patterns WHERE patternType = :patternType ORDER BY lastSeen DESC")
    fun getPatternsByTypeFlow(patternType: PatternType): Flow<List<LacCidPattern>>
    
    //endregion
    
    //region Aggregation Queries
    
    /**
     * Get geographic clusters of towers
     */
    @Query("""
        SELECT 
            ROUND(latitude, 3) as latGroup,
            ROUND(longitude, 3) as lonGroup,
            COUNT(*) as towerCount,
            GROUP_CONCAT(DISTINCT lac) as lacs,
            GROUP_CONCAT(DISTINCT cid) as cids,
            AVG(anomalyScore) as avgAnomalyScore
        FROM lac_cid_patterns 
        WHERE mcc = :mcc AND mnc = :mnc
        GROUP BY latGroup, lonGroup
        HAVING towerCount >= 2
        ORDER BY towerCount DESC
    """)
    suspend fun getGeographicClusters(mcc: Int, mnc: Int): List<GeographicClusterResult>
    
    /**
     * Get LACs with unusual CID distributions
     */
    @Query("""
        SELECT 
            lac,
            COUNT(DISTINCT cid) as uniqueCids,
            AVG(patternCount) as avgPatternCount,
            MAX(anomalyScore) as maxAnomalyScore
        FROM lac_cid_patterns 
        WHERE mcc = :mcc AND mnc = :mnc
        GROUP BY lac
        HAVING uniqueCids > :cidThreshold OR maxAnomalyScore > :anomalyThreshold
        ORDER BY uniqueCids DESC
    """)
    suspend fun getUnusualLacDistributions(
        mcc: Int, 
        mnc: Int, 
        cidThreshold: Int = 50,
        anomalyThreshold: Double = 70.0
    ): List<LacAnomalyResult>
    
    //endregion
    
    //region Update Operations for Pattern Evolution
    
    /**
     * Update pattern statistics when the same tower is observed again
     */
    @Query("""
        UPDATE lac_cid_patterns 
        SET 
            patternCount = patternCount + 1,
            lastSeen = :timestamp,
            avgDistanceFromCenter = (:newDistance + (patternCount * avgDistanceFromCenter)) / (patternCount + 1),
            maxDistanceFromCenter = MAX(maxDistanceFromCenter, :newDistance),
            minDistanceFromCenter = MIN(minDistanceFromCenter, :newDistance)
        WHERE id = :patternId
    """)
    suspend fun updatePatternStatistics(
        patternId: Long,
        timestamp: Long,
        newDistance: Double
    )
    
    /**
     * Mark a pattern as suspicious
     */
    @Query("""
        UPDATE lac_cid_patterns 
        SET 
            isSuspicious = 1,
            anomalyScore = :anomalyScore,
            confidenceScore = :confidenceScore,
            detectionReasons = :detectionReasons,
            lastSeen = :timestamp
        WHERE id = :patternId
    """)
    suspend fun markPatternAsSuspicious(
        patternId: Long,
        anomalyScore: Double,
        confidenceScore: Double,
        detectionReasons: String,
        timestamp: Long
    )
    
    //endregion
}

/**
 * Result class for LAC distribution analysis
 */
data class LacDistributionResult(
    val lac: Int,
    val towerCount: Int,
    val avgObservations: Double
)

/**
 * Result class for geographic cluster analysis
 */
data class GeographicClusterResult(
    val latGroup: Double,
    val lonGroup: Double,
    val towerCount: Int,
    val lacs: String,
    val cids: String,
    val avgAnomalyScore: Double
)

/**
 * Result class for LAC anomaly detection
 */
data class LacAnomalyResult(
    val lac: Int,
    val uniqueCids: Int,
    val avgPatternCount: Double,
    val maxAnomalyScore: Double
)