package com.cymatune.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface LocationHistoryDao {
    
    @Query("SELECT * FROM location_history WHERE cid = :cid AND lac = :lac AND mcc = :mcc AND mnc = :mnc LIMIT 1")
    suspend fun getLocationHistory(cid: Int, lac: Int, mcc: Int, mnc: Int): LocationHistory?
    
    @Query("SELECT * FROM location_history ORDER BY totalDurationMinutes DESC")
    fun getAllLocationHistory(): Flow<List<LocationHistory>>
    
    @Query("SELECT * FROM location_history WHERE totalDurationMinutes > :minMinutes ORDER BY totalDurationMinutes DESC LIMIT 3")
    suspend fun getFrequentLocations(minMinutes: Long = 60): List<LocationHistory>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLocationHistory(locationHistory: LocationHistory)
    
    @Update
    suspend fun updateLocationHistory(locationHistory: LocationHistory)
    
    @Query("DELETE FROM location_history WHERE lastSeen < :cutoffTime")
    suspend fun deleteOldHistory(cutoffTime: Long)
}
