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
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class RadioRepository(private val stationDao: StationDao, private val cacheDir: File) {

    private val PROXY_BASE_URL = "https://radio-joo-proxy.midoc11112.workers.dev"
    private val STATIONS_ENDPOINT = "$PROXY_BASE_URL/stations"
    private val ALL_STATIONS_ENDPOINT = "$PROXY_BASE_URL/stations/all"

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    val allStations: Flow<List<StationEntity>> = stationDao.getAllStations()

    suspend fun fetchStationsFromServer(): Int = withContext(Dispatchers.IO) {
        try {
            Log.d("RadioRepository", "جاري جلب المحطات...")

            val request = Request.Builder()
                .url(STATIONS_ENDPOINT)
                .addHeader("Accept", "application/json")
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e("RadioRepository", "فشل الاتصال: " + response.code)
                return@withContext 0
            }

            val jsonStr = response.body?.string() ?: return@withContext 0
            val jsonObject = JSONObject(jsonStr)
            val stationsArray = jsonObject.getJSONArray("stations")

            Log.d("RadioRepository", "عدد المحطات من السيرفر: " + stationsArray.length())

            val stations = parseStations(stationsArray)

            if (stations.isEmpty()) {
                Log.e("RadioRepository", "لا توجد محطات")
                return@withContext 0
            }

            // Validation حقيقي - نتأكد إن كل محطة شغالة
            val validated = validateStations(stations)

            Log.d("RadioRepository", "عدد المحطات الشغالة: " + validated.size)

            if (validated.isNotEmpty()) {
                saveStations(validated)
            }

            return@withContext validated.size

        } catch (e: Exception) {
            Log.e("RadioRepository", "خطأ: " + e.message)
            e.printStackTrace()
        }
        return@withContext 0
    }

    suspend fun fetchAllStationsInBackground() = withContext(Dispatchers.IO) {
        try {
            Log.d("RadioRepository", "جاري جلب كل المحطات...")

            val request = Request.Builder()
                .url(ALL_STATIONS_ENDPOINT)
                .addHeader("Accept", "application/json")
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) return@withContext

            val jsonStr = response.body?.string() ?: return@withContext
            val jsonObject = JSONObject(jsonStr)
            val stationsArray = jsonObject.getJSONArray("stations")

            val stations = parseStations(stationsArray)

            if (stations.isNotEmpty()) {
                val validated = validateStations(stations)
                if (validated.isNotEmpty()) {
                    saveStations(validated)
                    Log.d("RadioRepository", "تم حفظ " + validated.size + " محطة شغالة")
                }
            }

        } catch (e: Exception) {
            Log.e("RadioRepository", "خطأ في الخلفية: " + e.message)
        }
    }

    // Validation حقيقي - نتأكد إن كل محطة شغالة فعلاً
    private suspend fun validateStations(stations: List<StationEntity>): List<StationEntity> = withContext(Dispatchers.IO) {
        val validated = mutableListOf<StationEntity>()

        for (station in stations) {
            if (validated.size >= 150) break

            try {
                val isValid = validateOneStation(station.primaryUrl)
                if (isValid) {
                    validated.add(station)
                }
            } catch (e: Exception) {
                // محطة مش شغالة - نتخطاها
            }
        }

        return@withContext validated
    }

    private fun validateOneStation(url: String): Boolean {
        return try {
            // نجرب HEAD أولاً (أسرع)
            val headRequest = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", "RadioJoo/12.0")
                .header("Icy-MetaData", "0")
                .build()

            val headResponse = okHttpClient.newCall(headRequest).execute()

            if (headResponse.isSuccessful) {
                val contentType = headResponse.header("Content-Type") ?: ""
                if (!contentType.contains("video/") && !contentType.contains("text/html")) {
                    headResponse.close()
                    return true
                }
            }
            headResponse.close()

            // لو HEAD فشل، نجرب GET مع Range
            val getRequest = Request.Builder()
                .url(url)
                .header("User-Agent", "RadioJoo/12.0")
                .header("Icy-MetaData", "0")
                .header("Range", "bytes=0-0")
                .build()

            val getResponse = okHttpClient.newCall(getRequest).execute()

            if (getResponse.isSuccessful || getResponse.code == 206) {
                val contentType = getResponse.header("Content-Type") ?: ""
                getResponse.close()
                return !contentType.contains("video/") && !contentType.contains("text/html")
            }
            getResponse.close()

            false
        } catch (e: Exception) {
            false
        }
    }

    private fun parseStations(stationsArray: org.json.JSONArray): List<StationEntity> {
        val stations = mutableListOf<StationEntity>()

        for (i in 0 until stationsArray.length()) {
            try {
                val obj = stationsArray.getJSONObject(i)
                val uuid = obj.optString("uuid", "")
                val name = obj.optString("name", "")
                val primaryUrl = obj.optString("primaryUrl", "")

                if (uuid.isBlank() || primaryUrl.isBlank()) continue

                stations.add(StationEntity(
                    uuid = uuid,
                    name = name,
                    primaryUrl = primaryUrl,
                    fallbackUrl = obj.optString("fallbackUrl", ""),
                    category = obj.optString("category", "MUSIC"),
                    countryCode = obj.optString("countryCode", ""),
                    bitrate = obj.optInt("bitrate", 64),
                    isFavorite = false,
                    popularity = obj.optInt("popularity", 0),
                    lastUpdated = System.currentTimeMillis()
                ))
            } catch (e: Exception) {}
        }

        return stations
    }

    private suspend fun saveStations(stations: List<StationEntity>) {
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
    }

    suspend fun checkForUpdates(): Boolean = withContext(Dispatchers.IO) {
        try {
            val lastUpdate = stationDao.getLastUpdateTime()
            val currentTime = System.currentTimeMillis()
            val twoHours = 2 * 60 * 60 * 1000L

            if (lastUpdate == null || (currentTime - lastUpdate) > twoHours) {
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
            val count = stationDao.getStationsCount()
            if (count == 0) {
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
            PROXY_BASE_URL + "/?url=" + URLEncoder.encode(rawUrl, "UTF-8")
        } catch (e: Exception) {
            PROXY_BASE_URL + "/?url=" + rawUrl
        }
    }
}
