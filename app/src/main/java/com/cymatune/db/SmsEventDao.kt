package com.cymatune.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.util.*
import com.cymatune.util.ThreatLevel

/**
 * Data Access Object for SMS Event entities.
 * Provides CRUD operations and query methods for SMS event data.
 */
@Dao
interface SmsEventDao {
    
    /**
     * Insert a new SMS event
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSmsEvent(smsEvent: SMSEvent): Long
    
    /**
     * Insert multiple SMS events
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSmsEvents(smsEvents: List<SMSEvent>): List<Long>
    
    /**
     * Update an existing SMS event
     */
    @Update
    suspend fun updateSmsEvent(smsEvent: SMSEvent): Int
    
    /**
     * Delete an SMS event
     */
    @Delete
    suspend fun deleteSmsEvent(smsEvent: SMSEvent): Int
    
    /**
     * Get SMS event by ID
     */
    @Query("SELECT * FROM sms_events WHERE id = :id")
    suspend fun getSmsEventById(id: Long): SMSEvent?
    
    /**
     * Get all SMS events in chronological order
     */
    @Query("SELECT * FROM sms_events ORDER BY timestamp DESC")
    fun getAllSmsEvents(): Flow<List<SMSEvent>>
    
    /**
     * Get SMS events within a time range
     */
    @Query("SELECT * FROM sms_events WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp DESC")
    fun getSmsEventsByTimeRange(startTime: Long, endTime: Long): Flow<List<SMSEvent>>
    
    /**
     * Get SMS events by cell location
     */
    @Query("SELECT * FROM sms_events WHERE cid = :cid AND lac = :lac ORDER BY timestamp DESC")
    fun getSmsEventsByCell(cid: Int, lac: Int): Flow<List<SMSEvent>>
    
    /**
     * Get suspicious SMS events
     */
    @Query("SELECT * FROM sms_events WHERE isSuspicious = 1 ORDER BY timestamp DESC")
    fun getSuspiciousSmsEvents(): Flow<List<SMSEvent>>
    
    /**
     * Get SMS events by threat level
     */
    @Query("SELECT * FROM sms_events WHERE threatLevel = :threatLevel ORDER BY timestamp DESC")
    fun getSmsEventsByThreatLevel(threatLevel: ThreatLevel): Flow<List<SMSEvent>>
    
    /**
     * Get SMS events by sender number
     */
    @Query("SELECT * FROM sms_events WHERE senderNumber = :senderNumber ORDER BY timestamp DESC")
    fun getSmsEventsBySender(senderNumber: String): Flow<List<SMSEvent>>
    
    /**
     * Get SMS events by message type
     */
    @Query("SELECT * FROM sms_events WHERE messageType = :messageType ORDER BY timestamp DESC")
    fun getSmsEventsByMessageType(messageType: String): Flow<List<SMSEvent>>
    
    /**
     * Get recent SMS events (last N hours)
     */
    @Query("SELECT * FROM sms_events WHERE timestamp > :cutoffTime ORDER BY timestamp DESC")
    fun getRecentSmsEvents(cutoffTime: Long): Flow<List<SMSEvent>>
    
    /**
     * Get SMS events count for statistics
     */
    @Query("SELECT COUNT(*) FROM sms_events")
    suspend fun getSmsEventsCount(): Int
    
    /**
     * Get suspicious SMS events count
     */
    @Query("SELECT COUNT(*) FROM sms_events WHERE isSuspicious = 1")
    suspend fun getSuspiciousSmsEventsCount(): Int
    
    /**
     * Get SMS events count by threat level
     */
    @Query("SELECT COUNT(*) FROM sms_events WHERE threatLevel = :threatLevel")
    suspend fun getSmsEventsCountByThreatLevel(threatLevel: ThreatLevel): Int
    
    /**
     * Delete SMS events older than specified time
     */
    @Query("DELETE FROM sms_events WHERE timestamp < :cutoffTime")
    suspend fun deleteOldSmsEvents(cutoffTime: Long): Int
    
    /**
     * Get SMS events for correlation analysis with paging
     */
    @Query("""
        SELECT * FROM sms_events 
        WHERE timestamp BETWEEN :startTime AND :endTime 
        AND (isSuspicious = 1 OR threatLevel IN ('HIGH', 'CRITICAL'))
        ORDER BY timestamp DESC
    """)
    suspend fun getSmsEventsForCorrelation(startTime: Long, endTime: Long): List<SMSEvent>
    
    /**
     * Update SMS event analysis status
     */
    @Query("""
        UPDATE sms_events
        SET isAnalyzed = :analyzed, analysisTimestamp = :timestamp, analysisVersion = :version
        WHERE id = :id
    """)
    suspend fun updateAnalysisStatus(id: Long, analyzed: Boolean, timestamp: Long, version: String): Int
    
    /**
     * Get SMS events within a time range (synchronous version for direct access)
     * This method is used by SMSAndPagingIntegrationManager for correlation analysis
     */
    @Query("SELECT * FROM sms_events WHERE timestamp BETWEEN :startTime AND :endTime ORDER BY timestamp DESC")
    suspend fun getSmsEventsInTimeRange(startTime: Long, endTime: Long): List<SMSEvent>
}