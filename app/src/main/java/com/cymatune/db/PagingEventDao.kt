package com.cymatune.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for Paging Event tracking
 * 
 * Provides comprehensive data access operations for cellular network
 * paging events, supporting paging storm detection and IMSI catcher
 * identification through pattern analysis.
 */
@Dao
interface PagingEventDao {

    //region Basic CRUD Operations
    
    /**
     * Insert a new paging event or replace existing one
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPagingEvent(pagingEvent: PagingEvent): Long
    
    /**
     * Update an existing paging event
     */
    @Update
    suspend fun updatePagingEvent(pagingEvent: PagingEvent)
    
    /**
     * Delete a paging event by ID
     */
    @Query("DELETE FROM paging_events WHERE id = :id")
    suspend fun deletePagingEvent(id: Long)
    
    //endregion
    
    //region Event Retrieval by Identity and Time
    
    /**
     * Get all paging events ordered by timestamp (newest first)
     */
    @Query("SELECT * FROM paging_events ORDER BY timestamp DESC")
    fun getAllPagingEvents(): Flow<List<PagingEvent>>
    
    /**
     * Get paging events within a specific time range
     */
    @Query("SELECT * FROM paging_events WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp DESC")
    suspend fun getPagingEventsInTimeRange(startTime: Long, endTime: Long): List<PagingEvent>
    
    /**
     * Get recent paging events (last N hours)
     */
    @Query("SELECT * FROM paging_events WHERE timestamp > :cutoffTime ORDER BY timestamp DESC")
    suspend fun getRecentPagingEvents(cutoffTime: Long): List<PagingEvent>
    
    /**
     * Get paging events by event type
     */
    @Query("SELECT * FROM paging_events WHERE eventType = :eventType ORDER BY timestamp DESC")
    suspend fun getPagingEventsByType(eventType: PagingEventType): List<PagingEvent>
    
    //endregion
    
    //region Tower and Network Analysis
    
    /**
     * Get paging events for a specific tower (MCC-MNC-LAC-CID)
     */
    @Query("SELECT * FROM paging_events WHERE mcc = :mcc AND mnc = :mnc AND lac = :lac AND cid = :cid ORDER BY timestamp DESC")
    suspend fun getPagingEventsForTower(mcc: Int, mnc: Int, lac: Int, cid: Int): List<PagingEvent>
    
    /**
     * Get paging events for a specific tower with storm correlation
     */
    @Query("""
        SELECT * FROM paging_events 
        WHERE mcc = :mcc AND mnc = :mnc AND lac = :lac AND cid = :cid
        ORDER BY timestamp DESC
    """)
    suspend fun getPagingEventsForTowerWithStorms(mcc: Int, mnc: Int, lac: Int, cid: Int): List<PagingEvent>
    
    /**
     * Get paging events for a specific network (MCC-MNC)
     */
    @Query("SELECT * FROM paging_events WHERE mcc = :mcc AND mnc = :mnc ORDER BY timestamp DESC")
    suspend fun getPagingEventsForNetwork(mcc: Int, mnc: Int): List<PagingEvent>
    
    /**
     * Get unique towers with paging activity
     */
    @Query("""
        SELECT DISTINCT cid, lac, mcc, mnc, latitude, longitude
        FROM paging_events 
        WHERE timestamp > :sinceTime
        ORDER BY cid
    """)
    suspend fun getTowersWithPagingActivity(sinceTime: Long): List<TowerPagingSummary>
    
    //endregion
    
    //region Storm Detection and Analysis
    
    /**
     * Get paging events that are part of storms
     */
    @Query("SELECT * FROM paging_events WHERE isPartOfStorm = 1 ORDER BY timestamp DESC")
    suspend fun getStormRelatedPagingEvents(): List<PagingEvent>
    
    /**
     * Get paging events by storm ID
     */
    @Query("SELECT * FROM paging_events WHERE stormId = :stormId ORDER BY timestamp ASC")
    suspend fun getPagingEventsByStormId(stormId: Long): List<PagingEvent>
    
    /**
     * Get high-frequency paging events (potential storm indicators)
     */
    @Query("""
        SELECT 
            cid, lac, mcc, mnc,
            COUNT(*) as eventCount,
            MIN(timestamp) as startTime,
            MAX(timestamp) as endTime,
            AVG(signalStrength) as avgSignal,
            AVG(timingAdvance) as avgTiming
        FROM paging_events 
        WHERE timestamp > :windowStart
        GROUP BY cid, lac, mcc, mnc
        HAVING eventCount > :threshold
        ORDER BY eventCount DESC
    """)
    suspend fun getHighFrequencyPagingEvents(
        windowStart: Long,
        threshold: Int = 10
    ): List<HighFrequencyPagingResult>
    
    /**
     * Get paging events with high anomaly scores
     */
    @Query("SELECT * FROM paging_events WHERE anomalyScore > :threshold ORDER BY anomalyScore DESC")
    suspend fun getAnomalousPagingEvents(threshold: Double): List<PagingEvent>
    
    //endregion
    
    //region Signal and Timing Analysis
    
    /**
     * Get paging events by signal strength range
     */
    @Query("SELECT * FROM paging_events WHERE signalStrength BETWEEN :min AND :max ORDER BY signalStrength DESC")
    suspend fun getPagingEventsBySignalStrength(min: Int, max: Int): List<PagingEvent>
    
    /**
     * Get paging events by timing advance range
     */
    @Query("SELECT * FROM paging_events WHERE timingAdvance BETWEEN :min AND :max ORDER BY timingAdvance ASC")
    suspend fun getPagingEventsByTimingAdvance(min: Int, max: Int): List<PagingEvent>
    
    /**
     * Get signal strength patterns for tower analysis
     */
    @Query("""
        SELECT 
            cid, lac, mcc, mnc,
            AVG(signalStrength) as avgSignal,
            MIN(signalStrength) as minSignal,
            MAX(signalStrength) as maxSignal,
            COUNT(*) as eventCount
        FROM paging_events 
        WHERE timestamp > :sinceTime
        GROUP BY cid, lac, mcc, mnc
        ORDER BY avgSignal ASC
    """)
    suspend fun getSignalPatterns(sinceTime: Long): List<SignalPatternAnalysis>
    
    //endregion
    
    //region Statistical and Aggregation Queries
    
    /**
     * Count paging events by type
     */
    @Query("SELECT eventType, COUNT(*) as count FROM paging_events GROUP BY eventType ORDER BY count DESC")
    suspend fun countPagingEventsByType(): List<PagingEventTypeCount>
    
    /**
     * Count paging events by network type
     */
    @Query("SELECT networkType, COUNT(*) as count FROM paging_events GROUP BY networkType ORDER BY count DESC")
    suspend fun countPagingEventsByNetworkType(): List<NetworkTypeCount>
    
    /**
     * Get paging events per hour for trend analysis
     */
    @Query("""
        SELECT 
            strftime('%Y-%m-%d %H', datetime(timestamp/1000, 'unixepoch')) as hour,
            COUNT(*) as eventCount,
            SUM(CASE WHEN isPartOfStorm = 1 THEN 1 ELSE 0 END) as stormEvents,
            AVG(signalStrength) as avgSignal,
            AVG(anomalyScore) as avgAnomaly
        FROM paging_events 
        WHERE timestamp > :sinceTime
        GROUP BY hour
        ORDER BY hour DESC
    """)
    suspend fun getPagingEventsByHour(sinceTime: Long): List<HourlyPagingStats>
    
    /**
     * Get towers with unusual paging patterns
     */
    @Query("""
        SELECT 
            cid, lac, mcc, mnc,
            COUNT(*) as totalEvents,
            COUNT(DISTINCT eventType) as eventTypeVariety,
            AVG(signalStrength) as avgSignal,
            MAX(anomalyScore) as maxAnomaly,
            MIN(timestamp) as firstSeen,
            MAX(timestamp) as lastSeen
        FROM paging_events 
        WHERE timestamp > :sinceTime
        GROUP BY cid, lac, mcc, mnc
        HAVING totalEvents > :minEvents AND (maxAnomaly > :anomalyThreshold OR eventTypeVariety > 3)
        ORDER BY totalEvents DESC, maxAnomaly DESC
    """)
    suspend fun getUnusualPagingTowers(
        sinceTime: Long,
        minEvents: Int = 5,
        anomalyThreshold: Double = 70.0
    ): List<UnusualTowerAnalysis>
    
    //endregion
    
    //region Update Operations
    
    /**
     * Mark paging events as part of a storm
     */
    @Query("UPDATE paging_events SET isPartOfStorm = 1, stormId = :stormId WHERE id IN (:eventIds)")
    suspend fun markEventsAsStormRelated(eventIds: List<Long>, stormId: Long)
    
    /**
     * Update anomaly score for paging event
     */
    @Query("UPDATE paging_events SET anomalyScore = :anomalyScore, confidence = :confidence, analysisMetadata = :metadata WHERE id = :id")
    suspend fun updatePagingAnomalyScore(
        id: Long,
        anomalyScore: Double,
        confidence: Double,
        metadata: String?
    )
    
    /**
     * Update tower information for paging event
     */
    @Query("""
        UPDATE paging_events 
        SET 
            latitude = :latitude,
            longitude = :longitude
        WHERE id = :id
    """)
    suspend fun updatePagingEventLocation(id: Long, latitude: Double?, longitude: Double?)
    
    //endregion
    
    //region Cleanup and Maintenance
    
    /**
     * Delete old paging events (for data retention)
     */
    @Query("DELETE FROM paging_events WHERE timestamp < :cutoffTime")
    suspend fun deleteOldPagingEvents(cutoffTime: Long)
    
    /**
     * Get paging event statistics for dashboard
     */
    @Query("""
        SELECT 
            COUNT(*) as totalEvents,
            SUM(CASE WHEN isPartOfStorm = 1 THEN 1 ELSE 0 END) as stormEvents,
            COUNT(DISTINCT cid) as uniqueTowers,
            COUNT(DISTINCT eventType) as eventTypes,
            AVG(signalStrength) as avgSignal,
            AVG(anomalyScore) as avgAnomaly,
            MAX(timestamp) as latestEvent
        FROM paging_events
        WHERE timestamp > :sinceTime
    """)
    suspend fun getPagingStatistics(sinceTime: Long): PagingStatistics?
    
    //endregion
}

// Result classes for complex queries
data class TowerPagingSummary(
    val cid: Int,
    val lac: Int,
    val mcc: Int,
    val mnc: Int,
    val latitude: Double?,
    val longitude: Double?
)

data class HighFrequencyPagingResult(
    val cid: Int,
    val lac: Int,
    val mcc: Int,
    val mnc: Int,
    val eventCount: Int,
    val startTime: Long,
    val endTime: Long,
    val avgSignal: Double,
    val avgTiming: Double
)

data class SignalPatternAnalysis(
    val cid: Int,
    val lac: Int,
    val mcc: Int,
    val mnc: Int,
    val avgSignal: Double,
    val minSignal: Int,
    val maxSignal: Int,
    val eventCount: Int
)

data class PagingEventTypeCount(
    val eventType: PagingEventType,
    val count: Int
)

data class NetworkTypeCount(
    val networkType: String,
    val count: Int
)

data class HourlyPagingStats(
    val hour: String,
    val eventCount: Int,
    val stormEvents: Int,
    val avgSignal: Double,
    val avgAnomaly: Double
)

data class UnusualTowerAnalysis(
    val cid: Int,
    val lac: Int,
    val mcc: Int,
    val mnc: Int,
    val totalEvents: Int,
    val eventTypeVariety: Int,
    val avgSignal: Double,
    val maxAnomaly: Double,
    val firstSeen: Long,
    val lastSeen: Long
)

data class PagingStatistics(
    val totalEvents: Int,
    val stormEvents: Int,
    val uniqueTowers: Int,
    val eventTypes: Int,
    val avgSignal: Double,
    val avgAnomaly: Double,
    val latestEvent: Long?
)