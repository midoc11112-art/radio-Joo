package com.example.radiojooo.data.repository

import android.content.Context
import android.util.Log
import com.example.radiojooo.data.local.AppDatabase
import com.example.radiojooo.data.local.StationDao
import com.example.radiojooo.data.model.StationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
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
    private val healthClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).followRedirects(true).retryOnConnectionFailure(true).build()
    private val healthSemaphore = Semaphore(10)

    val allStations: Flow<List<StationEntity>> = stationDao.getVisibleStations()

    suspend fun fetchStationsFromServer(): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            val request = Request.Builder().url(STATIONS_ENDPOINT).addHeader("Accept", "application/json").build()
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

    suspend fun ensureInitialDataLoaded() {
        withContext(Dispatchers.IO) {
            try {
                Log.d("RadioRepository", "بدء جلب المحطات من السيرفر...")
                _healthTotal.value = 0
                _healthChecked.value = 0
                
                fetchStationsFromServer()
                Log.d("RadioRepository", "اكتمل جلب المحطات، استعداداً للفحص...")
                
                delay(500)

                val uncheckedAtStart = stationDao.getUncheckedCount()
                val staleAtStart = stationDao.getStaleVerifiedStations(System.currentTimeMillis() - 24 * 60 * 60 * 1000L, Int.MAX_VALUE).size
                val totalToCheck = uncheckedAtStart + staleAtStart
                _healthTotal.value = totalToCheck
                _healthChecked.value = 0

                if (totalToCheck > 0) {
                    Log.d("RadioRepository", "بدء فحص $totalToCheck محطة...")
                    var totalHealthy = 0
                    var safety = 0
                    while (safety < 50 && stationDao.getUncheckedCount() > 0) {
                        totalHealthy += runHealthChecks(batchSize = 30)
                        _healthChecked.value = minOf(_healthChecked.value + 30, totalToCheck)
                        safety++
                    }
                    stationDao.cleanupDeadStations()
                    _healthChecked.value = totalToCheck
                    _healthComplete.emit(HealthCheckResult(totalToCheck, totalHealthy))
                } else {
                    _healthComplete.emit(HealthCheckResult(0, 0))
                }
            } catch (e: Exception) { Log.e("RadioRepository", "InitError: ${e.message}") }
        }
    }

    suspend fun runHealthChecks(batchSize: Int = 30): Int = coroutineScope {
        var healthyCount = 0
        try {
            val unchecked = stationDao.getUncheckedStations(batchSize)
            val staleThreshold = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            val stale = stationDao.getStaleVerifiedStations(staleThreshold, batchSize / 2)
            val toCheck = (unchecked + stale).distinctBy { it.uuid }
            if (toCheck.isEmpty()) return@coroutineScope 0

            val results = toCheck.map { station ->
                async(Dispatchers.IO) { healthSemaphore.withPermit { station to checkStationHealth(station.primaryUrl) } }
            }.awaitAll()

            healthyCount = results.count { it.second }
            if (healthyCount == 0 && results.size >= 5) return@coroutineScope 0

            for ((station, works) in results) {
                val newFailCount = if (works) 0 else station.failCount + 1
                stationDao.updateHealth(station.uuid, works, newFailCount, System.currentTimeMillis())
            }
            stationDao.cleanupDeadStations()
        } catch (e: Exception) { Log.e("RadioRepository", "HealthCheck error: ${e.message}") }
        return@coroutineScope healthyCount
    }

    private suspend fun checkStationHealth(url: String): Boolean = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext false
        try {
            val healthCheckUrl = "$PROXY_BASE_URL/health-check?url=${URLEncoder.encode(url, "UTF-8")}"
            val request = Request.Builder().url(healthCheckUrl).build()
            healthClient.newCall(request).execute().use { response -> 
                response.isSuccessful 
            }
        } catch (e: Exception) { false }
    }

    suspend fun checkForUpdates(): Boolean = withContext(Dispatchers.IO) { false } 

    suspend fun toggleFavorite(uuid: String, currentStatus: Boolean) = stationDao.updateFavoriteStatus(uuid, !currentStatus)

    fun wrapProxy(rawUrl: String): String {
        if (rawUrl.isBlank()) return ""
        if (rawUrl.contains("radio-joo-proxy")) return rawUrl
        return try { PROXY_BASE_URL + "/?url=" + URLEncoder.encode(rawUrl, "UTF-8") } catch (e: Exception) { PROXY_BASE_URL + "/?url=" + rawUrl }
    }

    private fun parseStations(stationsArray: org.json.JSONArray): List<StationEntity> {
        val stations = mutableListOf<StationEntity>()
        for (i in 0 until stationsArray.length()) {
            try {
                val obj = stationsArray.getJSONObject(i)
                val uuid = obj.optString("uuid", ""); val name = obj.optString("name", ""); val primaryUrl = obj.optString("primaryUrl", "")
                if (uuid.isNotBlank() && primaryUrl.isNotBlank()) {
                    stations.add(StationEntity(uuid = uuid, name = name, primaryUrl = primaryUrl, fallbackUrl = obj.optString("fallbackUrl", ""), category = obj.optString("category", "MUSIC"), countryCode = obj.optString("countryCode", ""), bitrate = obj.optInt("bitrate", 64), popularity = obj.optInt("popularity", 0), lastUpdated = System.currentTimeMillis()))
                }
            } catch (e: Exception) {}
        }
        return stations
    }
}