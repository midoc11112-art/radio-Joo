package com.example.radiojooo.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.example.radiojooo.data.model.StationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StationDao {
    @Query("SELECT * FROM stations WHERE failCount < 3 OR isFavorite = 1 ORDER BY priority DESC, popularity DESC")
    fun getAllStations(): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE failCount < 3 OR isFavorite = 1 ORDER BY priority DESC, popularity DESC")
    fun getVisibleStations(): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE uuid = :uuid LIMIT 1")
    suspend fun getStationById(uuid: String): StationEntity?

    @Query("SELECT * FROM stations WHERE lastUrlWorks IS NULL ORDER BY priority DESC, popularity DESC LIMIT :limit")
    suspend fun getUncheckedStations(limit: Int): List<StationEntity>

    @Query("SELECT COUNT(*) FROM stations WHERE lastUrlWorks IS NULL")
    suspend fun getUncheckedCount(): Int

    @Query("SELECT COUNT(*) FROM stations")
    suspend fun getTotalCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStations(stations: List<StationEntity>)

    @Query("UPDATE stations SET priority = :priority WHERE uuid = :uuid")
    suspend fun updatePriority(uuid: String, priority: Int)

    @Transaction
    suspend fun syncPriorities(stations: List<StationEntity>) {
        for (s in stations) {
            try { updatePriority(s.uuid, s.priority) } catch (_: Throwable) {}
        }
    }

    @Query("UPDATE stations SET isFavorite = :isFavorite WHERE uuid = :uuid")
    suspend fun updateFavoriteStatus(uuid: String, isFavorite: Boolean)

    @Query("UPDATE stations SET lastUrlWorks = :works, failCount = :failCount, lastCheckedAt = :timestamp WHERE uuid = :uuid")
    suspend fun updateHealth(uuid: String, works: Boolean, failCount: Int, timestamp: Long)

    @Query("UPDATE stations SET primaryUrl = :newUrl, failCount = 0, lastUrlWorks = 1, lastCheckedAt = :ts WHERE uuid = :uuid")
    suspend fun updateStationUrl(uuid: String, newUrl: String, ts: Long = System.currentTimeMillis())

    @Query("UPDATE stations SET primaryUrl = :newUrl, fallbackUrl = :newFallback, failCount = 0, lastUrlWorks = 1, lastCheckedAt = :ts WHERE uuid = :uuid")
    suspend fun updateActiveStreamUrl(
        uuid: String,
        newUrl: String,
        newFallback: String,
        ts: Long = System.currentTimeMillis()
    )

    // حذف المحطة نهائياً إذا ثبت تلفها وليست مفضلة
    @Query("DELETE FROM stations WHERE isFavorite = 0 AND (failCount >= 3 OR lastUrlWorks = 0)")
    suspend fun cleanupDeadStations()

    @Query("DELETE FROM stations WHERE uuid = :uuid AND isFavorite = 0")
    suspend fun deleteDeadStation(uuid: String)

    @Query("UPDATE stations SET lastUrlWorks = NULL, failCount = 0")
    suspend fun resetAllHealthStatus()
}
