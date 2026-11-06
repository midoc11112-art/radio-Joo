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

    // عرض كل المحطات (بدون فلترة failCount) - المخفي فقط عند failCount >= 30
    @Query("SELECT * FROM stations WHERE failCount < 30 OR isFavorite = 1 ORDER BY popularity DESC")
    fun getVisibleStations(): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE uuid = :uuid LIMIT 1")
    suspend fun getStationById(uuid: String): StationEntity?

    @Query("SELECT * FROM stations WHERE lastUrlWorks IS NULL ORDER BY popularity DESC LIMIT :limit")
    suspend fun getUncheckedStations(limit: Int): List<StationEntity>

    @Query("SELECT * FROM stations WHERE lastUrlWorks = 1 AND lastCheckedAt < :threshold ORDER BY lastCheckedAt ASC LIMIT :limit")
    suspend fun getStaleVerifiedStations(threshold: Long, limit: Int): List<StationEntity>

    @Query("SELECT COUNT(*) FROM stations WHERE lastUrlWorks IS NULL")
    suspend fun getUncheckedCount(): Int

    @Query("SELECT COUNT(*) FROM stations")
    suspend fun getTotalCount(): Int

    @Query("SELECT MAX(lastUpdated) FROM stations")
    suspend fun getLastUpdateTime(): Long?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStations(stations: List<StationEntity>)

    @Query("UPDATE stations SET isFavorite = :isFavorite WHERE uuid = :uuid")
    suspend fun updateFavoriteStatus(uuid: String, isFavorite: Boolean)

    @Query("UPDATE stations SET lastUrlWorks = :works, failCount = :failCount, lastCheckedAt = :timestamp WHERE uuid = :uuid")
    suspend fun updateHealth(uuid: String, works: Boolean, failCount: Int, timestamp: Long)

    // يستخدمه RadioRepository.neuralHeal (نُبقيه كما هو - خط أحمر)
    @Query("UPDATE stations SET primaryUrl = :newUrl, failCount = 0, lastUrlWorks = 1, lastCheckedAt = :ts WHERE uuid = :uuid")
    suspend fun updateStationUrl(uuid: String, newUrl: String, ts: Long = System.currentTimeMillis())

    // 🆕 يستخدمه RadioService عند نجاح fallback: يحفظه كـ primary ويصفّر العداد
    @Query("UPDATE stations SET primaryUrl = :newUrl, fallbackUrl = :newFallback, failCount = 0, lastUrlWorks = 1, lastCheckedAt = :ts WHERE uuid = :uuid")
    suspend fun updateActiveStreamUrl(
        uuid: String,
        newUrl: String,
        newFallback: String,
        ts: Long = System.currentTimeMillis()
    )

    // لا حذف تلقائي - فقط عند failCount >= 30
    @Query("DELETE FROM stations WHERE isFavorite = 0 AND lastUrlWorks = 0 AND failCount >= 30")
    suspend fun cleanupDeadStations()

    @Query("UPDATE stations SET lastUrlWorks = NULL, failCount = 0")
    suspend fun resetAllHealthStatus()
}