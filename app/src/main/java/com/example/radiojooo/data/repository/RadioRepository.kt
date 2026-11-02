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

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    val allStations: Flow<List<StationEntity>> = stationDao.getAllStations()

    suspend fun fetchStationsFromServer(): Int = withContext(Dispatchers.IO) {
        try {
            Log.d("RadioRepository", "جاري جلب المحطات من السيرفر...")

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

            Log.d("RadioRepository", "عدد المحطات المستلمة: " + stationsArray.length())

            val stations = mutableListOf<StationEntity>()

            for (i in 0 until stationsArray.length()) {
                try {
                    val obj = stationsArray.getJSONObject(i)

                    val uuid = obj.optString("uuid", "")
                    val name = obj.optString("name", "محطة")
                    val primaryUrl = obj.optString("primaryUrl", "")

                    if (uuid.isBlank() || primaryUrl.isBlank()) continue

                    val station = StationEntity(
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
                    )

                    stations.add(station)
                } catch (e: Exception) {
                    Log.e("RadioRepository", "خطأ في قراءة محطة: " + e.message)
                }
            }

            if (stations.isEmpty()) {
                Log.e("RadioRepository", "لم يتم العثور على محطات صالحة")
                return@withContext 0
            }

            // حفظ المفضلة القديمة
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

            // حذف القديم وإضافة الجديد
            stationDao.deleteAllStations()
            stationDao.insertStations(updatedStations)

            Log.d("RadioRepository", "تم حفظ " + updatedStations.size + " محطة بنجاح")
            return@withContext updatedStations.size

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
            val count = stationDao.getStationsCount()
            Log.d("RadioRepository", "عدد المحطات الحالي: " + count)
            if (count == 0) {
                Log.d("RadioRepository", "قاعدة البيانات فارغة - جاري جلب المحطات...")
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
