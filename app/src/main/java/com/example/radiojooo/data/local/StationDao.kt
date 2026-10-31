package com.example.radiojooo.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.radiojooo.data.model.StationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StationDao {

    @Query("SELECT * FROM stations ORDER BY popularityScore DESC")
    fun getAllStations(): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations")
    suspend fun getStationsListDirect(): List<StationEntity>

    @Query("SELECT COUNT(*) FROM stations")
    suspend fun getStationsCount(): Int

    @Query("SELECT * FROM stations WHERE isFavorite = 1 ORDER BY popularityScore DESC")
    fun getFavoriteStations(): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE category = :category ORDER BY popularityScore DESC")
    fun getStationsByCategory(category: String): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE name LIKE '%' || :query || '%' ORDER BY popularityScore DESC")
    fun searchStations(query: String): Flow<List<StationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStations(stations: List<StationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStation(station: StationEntity)

    @Query("UPDATE stations SET isFavorite = :isFavorite WHERE uuid = :uuid")
    suspend fun updateFavorite(uuid: String, isFavorite: Boolean)

    @Query("UPDATE stations SET isFavorite = :isFavorite WHERE uuid = :uuid")
    suspend fun updateFavoriteStatus(uuid: String, isFavorite: Boolean)

    @Query("DELETE FROM stations")
    suspend fun clearAll()

    // ✅ التعديل هنا: تغيير streamUrl إلى url
    @Query("UPDATE stations SET url = :newUrl WHERE uuid = :uuid")
    suspend fun updateStreamUrl(uuid: String, newUrl: String)
}
