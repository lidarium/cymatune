package com.cymatune.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NeighborHistoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNeighborHistory(history: NeighborHistory)

    @Query("SELECT * FROM neighbor_history WHERE primaryCid = :cid AND primaryLac = :lac AND primaryMcc = :mcc AND primaryMnc = :mnc ORDER BY timestamp DESC LIMIT 10")
    suspend fun getRecentHistory(cid: Int, lac: Int, mcc: Int, mnc: Int): List<NeighborHistory>

    @Query("SELECT AVG(neighborCount) FROM neighbor_history WHERE primaryCid = :cid AND primaryLac = :lac AND primaryMcc = :mcc AND primaryMnc = :mnc")
    suspend fun getAverageNeighborCount(cid: Int, lac: Int, mcc: Int, mnc: Int): Double?
}
