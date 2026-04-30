package com.cymatune.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface ProtocolHandshakeDao {
    @Insert
    suspend fun insert(protocolHandshake: ProtocolHandshake): Long

    @Update
    suspend fun update(protocolHandshake: ProtocolHandshake)

    @Query("SELECT * FROM ProtocolHandshake WHERE towerId = :towerId ORDER BY timestamp DESC")
    suspend fun getHandshakesForTower(towerId: Int): List<ProtocolHandshake>

    @Query("SELECT * FROM ProtocolHandshake WHERE suspicionScore > :minScore ORDER BY timestamp DESC")
    suspend fun getSuspiciousHandshakes(minScore: Double = 0.6): List<ProtocolHandshake>

    @Query("SELECT * FROM ProtocolHandshake ORDER BY timestamp DESC")
    suspend fun getAll(): List<ProtocolHandshake>

    @Query("DELETE FROM ProtocolHandshake WHERE timestamp < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)

    @Query("SELECT COUNT(*) FROM ProtocolHandshake WHERE towerId = :towerId AND success = 1")
    suspend fun getSuccessfulHandshakeCount(towerId: Int): Int

    @Query("SELECT COUNT(*) FROM ProtocolHandshake WHERE towerId = :towerId  AND success = 0")
    suspend fun getFailedHandshakeCount(towerId: Int): Int

    @Query("SELECT AVG(suspicionScore) FROM ProtocolHandshake WHERE towerId = :towerId")
    suspend fun getAverageSuspicionScore(towerId: Int): Double
}