package com.example.radiojooo.data.repository

import android.util.Log
import com.example.radiojooo.data.local.StationDao
import com.example.radiojooo.data.model.StationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class RadioRepository(private val stationDao: StationDao, private val cacheDir: File) {

    private val PROXY_BASE_URL = "https://radio-joo-proxy.midoc11112.workers.dev"
    private val STATIONS_ENDPOINT = "$PROXY_BASE_URL/stations"

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    val allStations: Flow<List<StationEntity>> = stationDao.getAllStations()

    suspend fun fetchStationsFromServer(): Int = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(STATIONS_ENDPOINT)
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val jsonStr = response.body?.string() ?: return@withContext 0
                val jsonObject = JSONObject(jsonStr)
                val stationsArray = jsonObject.getJSONArray("stations")

                val stations = mutableListOf<StationEntity>()

                for (i in 0 until stationsArray.length()) {
                    val stationObj = stationsArray.getJSONObject(i)

                    val station = StationEntity(
                        uuid = stationObj.getString("uuid"),
                        name = stationObj.getString("name"),
                        primaryUrl = stationObj.getString("primaryUrl"),
                        fallbackUrl = stationObj.optString("fallbackUrl", ""),
                        category = stationObj.getString("category"),
                        countryCode = stationObj.getString("countryCode"),
                        bitrate = stationObj.optInt("bitrate", 64),
                        isFavorite = false,
                        popularity = stationObj.optInt("popularity", 0),
                        lastUpdated = System.currentTimeMillis()
                    )

                    stations.add(station)
                }

                val currentFavs = stationDao.getStationsListDirect()
                    .filter { it.isFavorite }
                    .map { it.uuid }
                    .toSet()

                val updatedStations = stations.map { station ->
                    if (station.uuid in currentFavs) {
                        station.copy(isFavorite = true)
                    } else {
                        station
                    }
                }

                stationDao.deleteAllStations()
                stationDao.insertStations(updatedStations)

                Log.d("RadioRepository", "تم تحديث " + stations.size + " محطة")
                return@withContext stations.size
            }
        } catch (e: Exception) {
            Log.e("RadioRepository", "خطأ في جلب المحطات: " + e.message)
            e.printStackTrace()
        }
        return@withContext 0
    }

    suspend fun checkForUpdates(): Boolean = withContext(Dispatchers.IO) {
        try {
            val lastUpdate = stationDao.getLastUpdateTime()
            val currentTime = System.currentTimeMillis()
            val twentyFourHours = 24 * 60 * 60 * 1000L

            if (lastUpdate == null || (currentTime - lastUpdate) > twentyFourHours) {
                fetchStationsFromServer()
                return@withContext true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext false
    }

    suspend fun ensureInitialDataLoaded() {
        withContext(Dispatchers.IO) {
            if (stationDao.getStationsCount() == 0) {
                fetchStationsFromServer()
            }
        }
    }

    suspend fun toggleFavorite(uuid: String, currentStatus: Boolean) {
        stationDao.updateFavoriteStatus(uuid, !currentStatus)
    }

    fun wrapProxy(rawUrl: String): String {
        if (rawUrl.isBlank()) return ""
        if (rawUrl.contains("radio-joo-proxy")) return rawUrl
        return try {
            "$PROXY_BASE_URL/?url=" + java.net.URLEncoder.encode(rawUrl, "UTF-8")
        } catch (e: Exception) {
            "$PROXY_BASE_URL/?url=$rawUrl"
        }
    }
}
