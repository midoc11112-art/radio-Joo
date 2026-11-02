package com.example.radiojooo.data.repository

import com.example.radiojooo.data.local.StationDao
import com.example.radiojooo.data.model.StationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

class RadioRepository(private val stationDao: StationDao) {

    val allStations: Flow<List<StationEntity>> = stationDao.getAllStations()

    suspend fun checkAndSeedInitialStations() {
        withContext(Dispatchers.IO) {
            val initialList = listOf(
                StationEntity(
                    uuid = "quran_cairo",
                    name = "إذاعة القرآن الكريم",
                    primaryUrl = "https://n0a.radiojar.com/8s5u5v3eb0uvv",
                    category = "ديني",
                    popularityScore = 100
                ),
                StationEntity(
                    uuid = "nogoum_fm",
                    name = "نجوم إف إم",
                    primaryUrl = "https://nr9.streamakaci.com/nogoumfm.mp3",
                    category = "منوعات",
                    popularityScore = 95
                ),
                StationEntity(
                    uuid = "radio_masr",
                    name = "راديو مصر",
                    primaryUrl = "https://stream.radiojoo.com/masr",
                    category = "أخبار",
                    popularityScore = 90
                ),
                StationEntity(
                    uuid = "aghani_fm",
                    name = "إذاعة الأغاني",
                    primaryUrl = "https://stream.radiojoo.com/aghani",
                    category = "موسيقى",
                    popularityScore = 85
                ),
                StationEntity(
                    uuid = "mega_fm",
                    name = "ميجا إف إم",
                    primaryUrl = "https://stream.radiojoo.com/mega",
                    category = "منوعات",
                    popularityScore = 80
                )
            )
            stationDao.insertStations(initialList)
        }
    }

    suspend fun toggleFavorite(station: StationEntity) {
        withContext(Dispatchers.IO) {
            stationDao.updateFavoriteStatus(station.uuid, !station.isFavorite)
        }
    }
}
