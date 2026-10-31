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

    @Query("SELECT * FROM stations ORDER BY popularityScore DESC")
    fun getStationsListDirect(): List<StationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStations(stations: List<StationEntity>)

    @Query("SELECT COUNT(*) FROM stations")
    fun getStationsCount(): Int

    @Query("UPDATE stations SET isFavorite = :isFav WHERE uuid = :uuid")
    suspend fun updateFavoriteStatus(uuid: String, isFav: Boolean)

    // ✅ الدالة الجديدة اللي بتحدث الرابط التلقائي
    @Query("UPDATE stations SET streamUrl = :newUrl WHERE uuid = :uuid")
    suspend fun updateStreamUrl(uuid: String, newUrl: String)
}
