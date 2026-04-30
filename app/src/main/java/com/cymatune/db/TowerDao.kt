package com.cymatune.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.cymatune.util.TowerInfo
import com.cymatune.util.TowerObservation
import kotlinx.coroutines.flow.Flow

@Dao
interface TowerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTowerInfo(towerInfo: TowerInfo)

    @Update
    suspend fun updateTowerInfo(towerInfo: TowerInfo)

    @Query("SELECT * FROM TowerInfo WHERE cid = :cid AND lac = :lac AND mcc = :mcc AND mnc = :mnc")
    suspend fun getTowerInfo(cid: Int, lac: Int, mcc: Int, mnc: Int): TowerInfo?

    @Query("SELECT * FROM TowerInfo")
    fun getAllTowerInfo(): Flow<List<TowerInfo>>

    @Query("""
        SELECT
            t.*,
            o.ta as latestTA,
            o.signal as latestSignal
        FROM TowerInfo t
        LEFT JOIN (
            SELECT cid, lac, mcc, mnc, ta, signal, timestamp
            FROM tower_observations
            WHERE (cid, lac, mcc, mnc, timestamp) IN (
                SELECT cid, lac, mcc, mnc, MAX(timestamp) as max_ts
                FROM tower_observations
                GROUP BY cid, lac, mcc, mnc
            )
        ) o ON t.cid = o.cid AND t.lac = o.lac AND t.mcc = o.mcc AND t.mnc = o.mnc
    """)
    fun getAllTowersWithDetails(): Flow<List<com.cymatune.util.TowerInfoWithDetails>>

    @Query("""
        SELECT
            t.*,
            o.ta as latestTA,
            o.signal as latestSignal
        FROM TowerInfo t
        LEFT JOIN (
            SELECT cid, lac, mcc, mnc, ta, signal, timestamp
            FROM tower_observations
            WHERE (cid, lac, mcc, mnc, timestamp) IN (
                SELECT cid, lac, mcc, mnc, MAX(timestamp) as max_ts
                FROM tower_observations
                GROUP BY cid, lac, mcc, mnc
            )
        ) o ON t.cid = o.cid AND t.lac = o.lac AND t.mcc = o.mcc AND t.mnc = o.mnc
        WHERE t.lastConnectedTime >= :sinceTime
    """)
    fun getRecentlyConnectedTowersWithDetails(sinceTime: Long): Flow<List<com.cymatune.util.TowerInfoWithDetails>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTowerObservation(observation: TowerObservation)

    @Query("SELECT * FROM tower_observations WHERE cid = :cid AND lac = :lac AND mcc = :mcc AND mnc = :mnc ORDER BY timestamp ASC LIMIT 1000")
    suspend fun getObservationsForTower(cid: Int, lac: Int, mcc: Int, mnc: Int): List<TowerObservation>

    @Query("""
        SELECT * FROM tower_observations
        WHERE cid = :cid AND lac = :lac AND mcc = :mcc AND mnc = :mnc
        AND timestamp >= :startTime
        ORDER BY timestamp DESC
        LIMIT 1000
    """)
    suspend fun getObservationsForTowerSince(cid: Int, lac: Int, mcc: Int, mnc: Int, startTime: Long): List<TowerObservation>

    @Query("""
        SELECT * FROM tower_observations
        WHERE cid = :cid AND lac = :lac AND mcc = :mcc AND mnc = :mnc
        AND timestamp BETWEEN :startTime AND :endTime
        ORDER BY timestamp ASC
        LIMIT 1000
    """)
    suspend fun getObservationsForTowerInRange(cid: Int, lac: Int, mcc: Int, mnc: Int, startTime: Long, endTime: Long): List<TowerObservation>

    @Query("SELECT * FROM tower_observations WHERE cid = :cid AND lac = :lac AND mcc = :mcc AND mnc = :mnc ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestObservationForTower(cid: Int, lac: Int, mcc: Int, mnc: Int): com.cymatune.util.TowerObservation?

    @Query("DELETE FROM TowerInfo")
    suspend fun clearAllTowerInfo()

    @Query("DELETE FROM tower_observations")
    suspend fun clearAllTowerObservations()
}
