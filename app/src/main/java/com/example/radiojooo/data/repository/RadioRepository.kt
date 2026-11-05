package com.example.radiojooo.data.repository

import android.content.Context
import android.util.Log
import com.example.radiojooo.data.local.AppDatabase
import com.example.radiojooo.data.local.StationDao
import com.example.radiojooo.data.model.StationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class HealthCheckResult(val total: Int, val healthy: Int)

class RadioRepository(
    private val context: Context,
    stationDaoParam: StationDao? = null,
    cacheDir: File? = null
) {
    private val stationDao: StationDao = stationDaoParam ?: AppDatabase.getDatabase(context).stationDao()
    private val PROXY_BASE_URL = "https://radio-joo-proxy.midoc11112.workers.dev"
    private val STATIONS_ENDPOINT = "$PROXY_BASE_URL/stations"

    private val _healthChecked = MutableStateFlow(0)
    val healthChecked: StateFlow<Int> = _healthChecked.asStateFlow()
    private val _healthTotal = MutableStateFlow(0)
    val healthTotal: StateFlow<Int> = _healthTotal.asStateFlow()
    private val _healthComplete = MutableSharedFlow<HealthCheckResult>(replay = 1, extraBufferCapacity = 1)
    val healthComplete: SharedFlow<HealthCheckResult> = _healthComplete.asSharedFlow()

    private val apiClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()

    val allStations: Flow<List<StationEntity>> = stationDao.getVisibleStations()

    suspend fun fetchStationsFromServer(): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            val request = Request.Builder().url(STATIONS_ENDPOINT).build()
            apiClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val jsonStr = response.body?.string()
                    if (jsonStr != null) {
                        val stationsArray = JSONObject(jsonStr).getJSONArray("stations")
                        count = stationsArray.length()
                        val serverStations = parseStations(stationsArray)
                        if (serverStations.isNotEmpty()) stationDao.insertStations(serverStations)
                    }
                }
            }
        } catch (e: Exception) { Log.e("RadioRepository", "Fetch error: ${e.message}") }
        return@withContext stationDao.getTotalCount()
    }

    suspend fun checkForUpdates(): Boolean = withContext(Dispatchers.IO) {
        try {
            val lastUpdate = stationDao.getLastUpdateTime() ?: 0L
            val currentTime = System.currentTimeMillis()
            val twoHours = 2 * 60 * 60 * 1000L
            if (lastUpdate == 0L || (currentTime - lastUpdate) > twoHours) {
                fetchStationsFromServer()
                return@withContext true
            }
        } catch (e: Exception) { e.printStackTrace() }
        return@withContext false
    }

    suspend fun ensureInitialDataLoaded() {
        withContext(Dispatchers.IO) {
            try {
                // جلب المحطات فقط بدون فحص تلقائي يخفيها
                if (stationDao.getTotalCount() < 50) {
                    fetchStationsFromServer()
                }
                // إرسال إشارة وهمية إن الفحص انتهى عشان شريط التحميل يختفي
                _healthTotal.value = 1
                _healthChecked.value = 1
                _healthComplete.emit(HealthCheckResult(1, 1))
            } catch (e: Exception) { Log.e("RadioRepository", "InitError: ${e.message}") }
        }
    }

    suspend fun toggleFavorite(uuid: String, currentStatus: Boolean) = stationDao.updateFavoriteStatus(uuid, !currentStatus)

    fun wrapProxy(rawUrl: String): String {
        if (rawUrl.isBlank()) return ""
        if (rawUrl.contains("radio-joo-proxy")) return rawUrl
        return try {
            PROXY_BASE_URL + "/?url=" + java.net.URLEncoder.encode(rawUrl, "UTF-8")
        } catch (e: Exception) { PROXY_BASE_URL + "/?url=" + rawUrl }
    }

    private fun parseStations(stationsArray: org.json.JSONArray): List<StationEntity> {
        val stations = mutableListOf<StationEntity>()
        for (i in 0 until stationsArray.length()) {
            try {
                val obj = stationsArray.getJSONObject(i)
                val uuid = obj.optString("uuid", ""); val name = obj.optString("name", ""); val primaryUrl = obj.optString("primaryUrl", "")
                if (uuid.isNotBlank() && primaryUrl.isNotBlank()) {
                    stations.add(StationEntity(uuid = uuid, name = name, primaryUrl = primaryUrl, fallbackUrl = obj.optString("fallbackUrl", ""), category = obj.optString("category", "MUSIC"), countryCode = obj.optString("countryCode", ""), bitrate = obj.optInt("bitrate", 64), popularity = obj.optInt("popularity", 0)))
                }
            } catch (e: Exception) {}
        }
        return stations
    }
}