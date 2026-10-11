package com.cymatune.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SignalBaselineDao {
    
    @Query("SELECT * FROM signal_baseline WHERE cid = :cid AND lac = :lac AND mcc = :mcc AND mnc = :mnc LIMIT 1")
    suspend fun getBaseline(cid: Int, lac: Int, mcc: Int, mnc: Int): SignalBaseline?
    
    @Query("SELECT * FROM signal_baseline WHERE sampleCount >= :minSamples ORDER BY sampleCount DESC")
    fun getAllBaselines(minSamples: Int = 10): Flow<List<SignalBaseline>>
    
    @Query("SELECT * FROM signal_baseline WHERE sampleCount >= :minSamples ORDER BY lastSeen DESC LIMIT :limit")
    suspend fun getRecentBaselines(minSamples: Int = 10, limit: Int = 50): List<SignalBaseline>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBaseline(baseline: SignalBaseline)
    
    @Update
    suspend fun updateBaseline(baseline: SignalBaseline)
    
    @Query("DELETE FROM signal_baseline WHERE lastSeen < :cutoffTime")
    suspend fun deleteOldBaselines(cutoffTime: Long)
    
    @Query("DELETE FROM signal_baseline WHERE sampleCount < :minSamples AND lastSeen < :cutoffTime")
    suspend fun cleanupLowSampleBaselines(minSamples: Int = 5, cutoffTime: Long)
}
