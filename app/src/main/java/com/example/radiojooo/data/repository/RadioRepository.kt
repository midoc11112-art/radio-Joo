package com.example.radiojooo.data.repository

import android.content.Context
import com.example.radiojooo.data.local.AppDatabase
import com.example.radiojooo.data.local.StationDao
import com.example.radiojooo.data.local.TrustedStations
import com.example.radiojooo.data.model.StationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class RadioRepository(context: Context) {

    private val db = AppDatabase.getDatabase(context)
    private val stationDao: StationDao = db.stationDao()

    val allStations: Flow<List<StationEntity>> = stationDao.getAllStations()
    val favoriteStations: Flow<List<StationEntity>> = stationDao.getFavoriteStations()

    fun getStationsByCategory(category: String): Flow<List<StationEntity>> {
        return stationDao.getStationsByCategory(category)
    }

    fun searchStations(query: String): Flow<List<StationEntity>> {
        return stationDao.searchStations(query)
    }

    suspend fun ensureInitialDataLoaded() {
        withContext(Dispatchers.IO) {
            val count = stationDao.getStationCount()
            if (count == 0) {
                val initialStations = TrustedStations.getInitialStations()
                stationDao.insertStations(initialStations)
            }
        }
    }

    suspend fun fetchStationsFromServer(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val serverUrl = "https://radio-joo-proxy.midoc11112.workers.dev/stations"
                val connection = (URL(serverUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 12000
                    readTimeout = 12000
                    setRequestProperty("User-Agent", "RadioJooApp/27.0")
                    setRequestProperty("Accept", "application/json")
                }

                if (connection.responseCode == 200) {
                    val jsonString = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonObject = JSONObject(jsonString)
                    val stationsArray = jsonObject.getJSONArray("stations")

                    val stationsList = mutableListOf<StationEntity>()
                    val now = System.currentTimeMillis()

                    for (i in 0 until stationsArray.length()) {
                        val obj = stationsArray.getJSONObject(i)
                        val uuid = obj.optString("uuid", "")
                        val name = obj.optString("name", "محطة راديو")
                        val primaryUrl = obj.optString("primaryUrl", "")
                        val fallbackUrl = obj.optString("fallbackUrl", "")
                        val category = obj.optString("category", "MUSIC")
                        val countryCode = obj.optString("countryCode", "EG")
                        val bitrate = obj.optInt("bitrate", 128)
                        val popularity = obj.optInt("popularity", 0)

                        if (primaryUrl.isNotEmpty() && uuid.isNotEmpty()) {
                            val existingStation = stationDao.getStationByUuid(uuid)
                            val isFavorite = existingStation?.isFavorite ?: false

                            stationsList.add(
                                StationEntity(
                                    uuid = uuid,
                                    name = name,
                                    primaryUrl = primaryUrl,
                                    fallbackUrl = fallbackUrl,
                                    category = category,
                                    countryCode = countryCode,
                                    bitrate = bitrate,
                                    popularity = popularity,
                                    isFavorite = isFavorite,
                                    lastUpdated = now
                                )
                            )
                        }
                    }

                    if (stationsList.isNotEmpty()) {
                        stationDao.insertStations(stationsList)
                        return@withContext true
                    }
                }
                false
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    suspend fun toggleFavorite(uuid: String) {
        withContext(Dispatchers.IO) {
            val station = stationDao.getStationByUuid(uuid)
            station?.let {
                stationDao.updateFavoriteStatus(uuid, !it.isFavorite)
            }
        }
    }

    suspend fun getStationByUuid(uuid: String): StationEntity? {
        return withContext(Dispatchers.IO) {
            stationDao.getStationByUuid(uuid)
        }
    }
}
