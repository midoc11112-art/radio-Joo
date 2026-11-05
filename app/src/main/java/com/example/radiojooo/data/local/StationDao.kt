package com.example.radiojooo.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.radiojooo.data.model.StationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StationDao {
    @Query("SELECT * FROM stations ORDER BY popularity DESC")
    fun getAllStations(): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE isFavorite = 1 ORDER BY popularity DESC")
    fun getFavoriteStations(): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE category = :category ORDER BY popularity DESC")
    fun getStationsByCategory(category: String): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE name LIKE '%' || :query || '%' OR category LIKE '%' || :query || '%' ORDER BY popularity DESC")
    fun searchStations(query: String): Flow<List<StationEntity>>

    @Query("SELECT COUNT(*) FROM stations")
    suspend fun getStationCount(): Int

    @Query("SELECT * FROM stations WHERE uuid = :uuid LIMIT 1")
    suspend fun getStationByUuid(uuid: String): StationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStations(stations: List<StationEntity>)

    @Query("UPDATE stations SET isFavorite = :isFavorite WHERE uuid = :uuid")
    suspend fun updateFavoriteStatus(uuid: String, isFavorite: Boolean)

    @Update
    suspend fun update(station: StationEntity)

    @Query("DELETE FROM stations")
    suspend fun deleteAll()
}
