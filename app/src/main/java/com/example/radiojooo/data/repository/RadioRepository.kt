package com.example.radiojooo.data.repository

import android.content.Context
import android.util.Log
import com.example.radiojooo.data.local.AppDatabase
import com.example.radiojooo.data.local.StationDao
import com.example.radiojooo.data.model.StationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class RadioRepository(
    private val context: Context,
    stationDaoParam: StationDao? = null,
    cacheDir: File? = null
) {

    private val stationDao: StationDao = stationDaoParam
        ?: AppDatabase.getDatabase(context).stationDao()

    private val PROXY_BASE_URL = "https://radio-joo-proxy.midoc11112.workers.dev"
    private val STATIONS_ENDPOINT = "$PROXY_BASE_URL/stations"

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val allStations: Flow<List<StationEntity>> = stationDao.getAllStations()

    suspend fun fetchStationsFromServer(): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            Log.d("RadioRepository", "جاري جلب المحطات من السيرفر...")

            val request = Request.Builder()
                .url(STATIONS_ENDPOINT)
                .addHeader("Accept", "application/json")
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (response.isSuccessful) {
                val jsonStr = response.body?.string()
                if (jsonStr != null) {
                    val jsonObject = JSONObject(jsonStr)
                    val stationsArray = jsonObject.getJSONArray("stations")
                    count = stationsArray.length()
                    Log.d("RadioRepository", "السيرفر بعت: $count محطة")

                    val serverStations = parseStations(stationsArray)
                    if (serverStations.isNotEmpty()) {
                        stationDao.insertStations(serverStations)
                    }
                }
            } else {
                Log.e("RadioRepository", "فشل: " + response.code)
            }
        } catch (e: Exception) {
            Log.e("RadioRepository", "خطأ في جلب السيرفر: " + e.message)
        }

        val total = stationDao.getAllStations().first().size
        Log.d("RadioRepository", "الإجمالي في قاعدة البيانات: $total محطة")
        return@withContext total
    }

    suspend fun checkForUpdates(): Boolean = withContext(Dispatchers.IO) {
        try {
            val all = stationDao.getAllStations().first()
            val lastUpdate = all.maxOfOrNull { it.lastUpdated } ?: 0L
            val currentTime = System.currentTimeMillis()
            val twoHours = 2 * 60 * 60 * 1000L

            if (lastUpdate == 0L || (currentTime - lastUpdate) > twoHours) {
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
            val count = stationDao.getAllStations().first().size
            Log.d("RadioRepository", "عدد المحطات الحالي: $count")
            if (count < 50) {
                Log.d("RadioRepository", "عدد المحطات قليل - جاري جلب من السيرفر...")
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
}
