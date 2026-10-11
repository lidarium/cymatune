package com.cymatune.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FakeTowerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFakeTower(fakeTower: FakeTower)
    
    /**
     * Enhanced insert method that prevents duplicates by checking recent entries
     * This method checks for existing towers within the last 5 minutes before inserting
     */
    @Query("""
        INSERT OR REPLACE INTO FakeTower (cid, lac, mcc, mnc, latitude, longitude, detectionTime, accuracy, reason, signalStrength, networkType, observationCount, precisionRadius, isTriangulated, trustScore, rsrq, sinr, pci, arfcn, timingAdvance, cipherStrength, encryptionProtocol, authenticationMethod, securityCapabilities, hasHistoricThreat, lastThreatTimestamp)
        SELECT :cid, :lac, :mcc, :mnc, :latitude, :longitude, :detectionTime, :accuracy, :reason, :signalStrength, :networkType, :observationCount, :precisionRadius, :isTriangulated, :trustScore, :rsrq, :sinr, :pci, :arfcn, :timingAdvance, :cipherStrength, :encryptionProtocol, :authenticationMethod, :securityCapabilities, :hasHistoricThreat, :lastThreatTimestamp
        WHERE NOT EXISTS (
            SELECT 1 FROM FakeTower
            WHERE cid = :cid AND lac = :lac AND mcc = :mcc AND mnc = :mnc
            AND detectionTime > :cutoffTime
        )
    """)
    suspend fun insertFakeTowerIfNotRecent(
        cid: Int,
        lac: Int,
        mcc: Int,
        mnc: Int,
        latitude: Double,
        longitude: Double,
        detectionTime: Long,
        accuracy: Double,
        reason: String,
        signalStrength: Int,
        networkType: String,
        observationCount: Int,
        precisionRadius: Double,
        isTriangulated: Boolean,
        trustScore: Int,
        rsrq: Int?,
        sinr: Int?,
        pci: Int,
        arfcn: Int,
        timingAdvance: Int,
        cipherStrength: Int = 0,
        encryptionProtocol: String? = null,
        authenticationMethod: String? = null,
        securityCapabilities: String? = null,
        cutoffTime: Long = System.currentTimeMillis() - 300000, // 5 minutes
        hasHistoricThreat: Boolean = false,
        lastThreatTimestamp: Long? = null
    )
    
    /**
     * Update existing tower's observation stats (Count increment, Time update)
     */
    @Query("UPDATE FakeTower SET observationCount = observationCount + 1, detectionTime = :now, signalStrength = :rssi WHERE cid = :cid AND lac = :lac AND mcc = :mcc AND mnc = :mnc")
    suspend fun updateTowerObservation(cid: Int, lac: Int, mcc: Int, mnc: Int, now: Long, rssi: Int)

    @Query("SELECT * FROM FakeTower ORDER BY detectionTime DESC LIMIT 1000")
    fun getAllFakeTowers(): Flow<List<FakeTower>>
    
    @Query("SELECT * FROM FakeTower ORDER BY detectionTime DESC LIMIT 1000")
    suspend fun getAllFakeTowersSync(): List<FakeTower>

    @Query("SELECT * FROM FakeTower WHERE accuracy >= :minAccuracy ORDER BY detectionTime DESC")
    fun getFakeTowersByAccuracy(minAccuracy: Double): Flow<List<FakeTower>>

    @Query("SELECT * FROM FakeTower WHERE detectionTime >= :sinceTime ORDER BY detectionTime DESC")
    fun getRecentFakeTowers(sinceTime: Long): Flow<List<FakeTower>>

    @Query("SELECT * FROM FakeTower WHERE cid = :cid AND lac = :lac AND mcc = :mcc AND mnc = :mnc ORDER BY detectionTime DESC LIMIT 1")
    suspend fun getFakeTower(cid: Int, lac: Int, mcc: Int, mnc: Int): FakeTower?

    @Query("SELECT * FROM FakeTower ORDER BY detectionTime DESC LIMIT 500")
    fun getConsolidatedFakeTowers(): Flow<List<FakeTower>>

    @Query("DELETE FROM FakeTower WHERE detectionTime < :olderThanTime")
    suspend fun deleteOldFakeTowers(olderThanTime: Long)

    @Query("DELETE FROM FakeTower")
    suspend fun clearAllFakeTowers()
    
    @Query("DELETE FROM FakeTower WHERE id = :id")
    suspend fun deleteTowerById(id: Long)
    
    @Query("DELETE FROM FakeTower WHERE cid = :cid")
    suspend fun deleteTowerByCid(cid: Int)
    
    @Query("UPDATE FakeTower SET observationCount = :count, precisionRadius = :radius WHERE cid = :cid AND lac = :lac AND mcc = :mcc AND mnc = :mnc")
    suspend fun updateTowerPrecision(cid: Int, lac: Int, mcc: Int, mnc: Int, count: Int, radius: Double)

    @Query("SELECT * FROM FakeTower WHERE observationCount >= :minObservations ORDER BY precisionRadius ASC")
    suspend fun getTowersByObservationCount(minObservations: Int): List<FakeTower>

    @Query("SELECT * FROM FakeTower WHERE precisionRadius <= :maxPrecision ORDER BY precisionRadius ASC")
    suspend fun getTowersByPrecision(maxPrecision: Double): List<FakeTower>

    @Query("SELECT AVG(precisionRadius) FROM FakeTower WHERE observationCount >= 3")
    suspend fun getAveragePrecisionRadius(): Double?

    @Query("SELECT COUNT(*) FROM FakeTower WHERE observationCount >= 5 AND precisionRadius <= 500")
    suspend fun getHighPrecisionTowerCount(): Int

    @Query("SELECT COUNT(*) FROM FakeTower")
    suspend fun getTotalTowerCount(): Int

    @Query("UPDATE FakeTower SET latitude = :latitude, longitude = :longitude, precisionRadius = :precisionRadius, observationCount = :observationCount WHERE cid = :cid AND lac = :lac AND mcc = :mcc AND mnc = :mnc")
    suspend fun updateTowerLocation(cid: Int, lac: Int, mcc: Int, mnc: Int, latitude: Double, longitude: Double, precisionRadius: Double, observationCount: Int)

    @Query("UPDATE FakeTower SET isTriangulated = 1 WHERE cid = :cid AND lac = :lac AND mcc = :mcc AND mnc = :mnc")
    suspend fun markTowerAsTriangulated(cid: Int, lac: Int, mcc: Int, mnc: Int)

    @Query("SELECT * FROM FakeTower WHERE isTriangulated = 1 ORDER BY detectionTime DESC")
    fun getTriangulatedTowers(): Flow<List<FakeTower>>
    
    @Query("SELECT * FROM tower_observations WHERE cid = :cid AND lac = :lac AND mcc = :mcc AND mnc = :mnc ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestObservationForTower(cid: Int, lac: Int, mcc: Int, mnc: Int): com.cymatune.util.TowerObservation?
    
    /**
     * Get consolidated fake towers with deduplication - keeps only the most recent entry for each unique tower
     * This method groups towers by their unique identifier (CID-LAC-MCC-MNC) and returns the most recent one
     */
    /**
     * Get deduplicated fake towers for display - removes duplicates by keeping most recent
     * This method groups towers by their unique identifier (CID-LAC-MCC-MNC) and returns the most recent one
     */
    @Query("""
        SELECT ft.* FROM FakeTower ft
        INNER JOIN (
            SELECT cid, lac, mcc, mnc, MAX(detectionTime) as maxTime
            FROM FakeTower
            GROUP BY cid, lac, mcc, mnc
        ) grouped ON ft.cid = grouped.cid
            AND ft.lac = grouped.lac
            AND ft.mcc = grouped.mcc
            AND ft.mnc = grouped.mnc
            AND ft.detectionTime = grouped.maxTime
        ORDER BY ft.detectionTime DESC
        LIMIT 1000
    """)
    fun getDeduplicatedFakeTowers(): Flow<List<FakeTower>>
    
    /**
     * Debug query to check for duplicates in the database
     * This helps identify if the deduplication query is working correctly
     */
    @Query("""
        SELECT cid, lac, mcc, mnc, COUNT(*) as duplicate_count,
               MIN(detectionTime) as first_seen, MAX(detectionTime) as last_seen
        FROM FakeTower
        GROUP BY cid, lac, mcc, mnc
        HAVING COUNT(*) > 1
        ORDER BY duplicate_count DESC, last_seen DESC
        LIMIT 20
    """)
    suspend fun getDuplicateTowers(): List<TowerDuplicateInfo>
    
    /**
     * Debug query to get total tower count before and after deduplication
     */
    @Query("SELECT COUNT(*) FROM FakeTower")
    suspend fun getTotalTowerCountBeforeDeduplication(): Int
    
    @Query("""
        SELECT COUNT(*) FROM (
            SELECT cid, lac, mcc, mnc, MAX(detectionTime) as maxTime
            FROM FakeTower
            GROUP BY cid, lac, mcc, mnc
        )
    """)
    suspend fun getTotalTowerCountAfterDeduplication(): Int
    
    /**
     * Remove duplicate fake towers from database - keeps only the most recent entry for each unique tower
     * This is a maintenance method to clean up the database
     */
    @Query("""
        DELETE FROM FakeTower
        WHERE id NOT IN (
            SELECT id FROM (
                SELECT id, ROW_NUMBER() OVER (
                    PARTITION BY cid, lac, mcc, mnc
                    ORDER BY detectionTime DESC
                ) as row_num
                        FROM FakeTower
                    ) where row_num = 1
                )
            """)
            suspend fun removeDuplicateFakeTowers()
            
            /**
             * Get accurate threat count from deduplicated data
             * This method provides a direct database query for threat counts, avoiding
             * the need to fetch all towers and count in memory
             */
            @Query("""
                SELECT COUNT(*) FROM (
                    SELECT ft.* FROM FakeTower ft
                    INNER JOIN (
                        SELECT cid, lac, mcc, mnc, MAX(detectionTime) as maxTime
                        FROM FakeTower
                        GROUP BY cid, lac, mcc, mnc
                    ) grouped ON ft.cid = grouped.cid
                        AND ft.lac = grouped.lac
                        AND ft.mcc = grouped.mcc
                        AND ft.mnc = grouped.mnc
                        AND ft.detectionTime = grouped.maxTime
                ) deduplicated_towers
                WHERE trustScore < 50 OR hasHistoricThreat = 1
            """)
            suspend fun getThreatCount(): Int
            
            /**
             * Get accurate total count from deduplicated data
             * This method provides a direct database query for total tower counts, avoiding
             * the need to fetch all towers and count in memory
             */
    /**
     * Get accurate total count from deduplicated data
     * This method provides a direct database query for total tower counts, avoiding
     * the need to fetch all towers and count in memory
     */
    @Query("""
        SELECT COUNT(*) FROM (
            SELECT ft.* FROM FakeTower ft
            INNER JOIN (
                SELECT cid, lac, mcc, mnc, MAX(detectionTime) as maxTime
                FROM FakeTower
                GROUP BY cid, lac, mcc, mnc
            ) grouped ON ft.cid = grouped.cid
                AND ft.lac = grouped.lac
                AND ft.mcc = grouped.mcc
                AND ft.mnc = grouped.mnc
                AND ft.detectionTime = grouped.maxTime
        ) deduplicated_towers
    """)
    suspend fun getTotalDeduplicatedCount(): Int
    
    @Query("UPDATE FakeTower SET hasHistoricThreat = :hasThreat, lastThreatTimestamp = :timestamp WHERE cid = :cid AND lac = :lac AND mcc = :mcc AND mnc = :mnc")
    suspend fun updateHistoricThreatStatus(cid: Int, lac: Int, mcc: Int, mnc: Int, hasThreat: Boolean, timestamp: Long)

    @Query("SELECT * FROM FakeTower WHERE id = :id")
    suspend fun getFakeTowerById(id: Long): FakeTower?

    @Query("SELECT * FROM FakeTower WHERE cid = :cid ORDER BY detectionTime DESC LIMIT 1")
    suspend fun getAnyTowerByCid(cid: Int): FakeTower?
    
    /**
     * Get observation count for a specific tower to determine if it has stable history
     * Used for false positive reduction: new towers need 3+ observations before triggering alerts
     */
    @Query("""
        SELECT COUNT(*) FROM FakeTower 
        WHERE cid = :cid AND lac = :lac AND mcc = :mcc AND mnc = :mnc
    """)
    suspend fun getObservationCount(cid: Int, lac: Int, mcc: Int, mnc: Int): Int
}
