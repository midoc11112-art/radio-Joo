package com.example.radiojooo.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.radiojooo.data.model.StationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StationDao {
    
    @Query("SELECT * FROM stations ORDER BY popularity DESC")
    fun getAllStations(): Flow<List<StationEntity>>
    
    @Query("SELECT * FROM stations WHERE uuid = :uuid")
    suspend fun getStationById(uuid: String): StationEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStations(stations: List<StationEntity>)
    
    @Query("DELETE FROM stations")
    suspend fun deleteAllStations()
    
    @Query("UPDATE stations SET isFavorite = :isFavorite WHERE uuid = :uuid")
    suspend fun updateFavoriteStatus(uuid: String, isFavorite: Boolean)
    
    @Query("SELECT COUNT(*) FROM stations")
    suspend fun getStationsCount(): Int
    
    @Query("SELECT * FROM stations")
    suspend fun getStationsListDirect(): List<StationEntity>
    
    @Query("SELECT MAX(lastUpdated) FROM stations")
    suspend fun getLastUpdateTime(): Long?
}
