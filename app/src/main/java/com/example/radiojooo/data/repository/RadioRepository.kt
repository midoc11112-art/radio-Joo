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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class HealthCheckResult(val total: Int, val healthy: Int)
data class HealResult(val url: String, val matchedName: String)

class RadioRepository(
    private val context: Context,
    stationDaoParam: StationDao? = null,
    cacheDir: File? = null
) {
    private val stationDao: StationDao = stationDaoParam ?: AppDatabase.getDatabase(context).stationDao()
    private val PROXY_BASE_URL = "https://radio-joo-proxy.midoc11112.workers.dev"

    private val _healthChecked = MutableStateFlow(0)
    val healthChecked: StateFlow<Int> = _healthChecked.asStateFlow()
    private val _healthTotal = MutableStateFlow(0)
    val healthTotal: StateFlow<Int> = _healthTotal.asStateFlow()
    private val _healthComplete = MutableSharedFlow<HealthCheckResult>(replay = 1, extraBufferCapacity = 1)
    val healthComplete: SharedFlow<HealthCheckResult> = _healthComplete.asSharedFlow()

    private val apiClient = OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val healthClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).followRedirects(true).build()
    private val healthSemaphore = Semaphore(200)
    private val dbMutex = Mutex()

    val allStations: Flow<List<StationEntity>> = stationDao.getVisibleStations()

    suspend fun neuralHeal(station: StationEntity): HealResult? = withContext(Dispatchers.IO) {
        try {
            val url = "$PROXY_BASE_URL/heal?uuid=${URLEncoder.encode(station.uuid, "UTF-8")}&name=${URLEncoder.encode(station.name, "UTF-8")}&country=${station.countryCode}&category=${station.category}"
            apiClient.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    if (json.optBoolean("success", false)) {
                        val newUrl = json.optString("newUrl")
                        val matchedName = json.optString("matchedName", station.name)
                        if (newUrl.isNotBlank() && newUrl.startsWith("http")) {
                            runCatching { stationDao.updateStationUrl(station.uuid, newUrl) }
                            return@withContext HealResult(newUrl, matchedName)
                        }
                    }
                }
            }
        } catch (t: Throwable) { Log.e("HealAI", "فشل: ${t.message}") }
        return@withContext null
    }

    suspend fun sendFeedback(uuid: String, source: String, success: Boolean, errorType: String = "none", country: String = "", category: String = "") = withContext(Dispatchers.IO) {
        try {
            val fb = "$PROXY_BASE_URL/feedback?uuid=${URLEncoder.encode(uuid, "UTF-8")}&source=${URLEncoder.encode(source, "UTF-8")}&success=$success&error=${URLEncoder.encode(errorType, "UTF-8")}&country=${URLEncoder.encode(country, "UTF-8")}&category=${URLEncoder.encode(category, "UTF-8")}"
            apiClient.newCall(Request.Builder().url(fb).build()).execute().close()
        } catch (_: Throwable) {}
    }

    suspend fun fetchStationsFromServer(): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            apiClient.newCall(Request.Builder().url("$PROXY_BASE_URL/stations").build()).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    val arr = json.getJSONArray("stations")
                    count = arr.length()
                    val stations = parseStations(arr)
                    if (stations.isNotEmpty()) stationDao.insertStations(stations)
                }
            }
        } catch (t: Throwable) { Log.e("RadioRepository", "Fetch: ${t.message}") }
        return@withContext stationDao.getTotalCount()
    }

    // ✅ لا فحص تلقائي - فقط جلب + عرض كل المحطات
    suspend fun ensureInitialDataLoaded() {
        try {
            dbMutex.withLock {
                _healthTotal.value = 0
                _healthChecked.value = 0
                fetchStationsFromServer()
            }
            // لا performHealthChecks هنا!
            _healthComplete.emit(HealthCheckResult(0, 0))
        } catch (t: Throwable) { Log.e("RadioRepository", "Init: ${t.message}") }
    }

    // ✅ الريفريش اليدوي فقط يفحص
    suspend fun forceRefreshAll() {
        try {
            dbMutex.withLock {
                stationDao.resetAllHealthStatus()
                fetchStationsFromServer()
            }
            delay(500)
            performHealthChecks()
        } catch (t: Throwable) { Log.e("RadioRepository", "Refresh: ${t.message}") }
    }

    private suspend fun performHealthChecks() {
        val total = stationDao.getUncheckedCount()
        _healthTotal.value = total
        _healthChecked.value = 0
        if (total > 0) {
            var healthy = 0; var safety = 0
            while (safety < 200 && stationDao.getUncheckedCount() > 0) {
                healthy += runHealthChecks(200)
                _healthChecked.value = minOf(_healthChecked.value + 200, total)
                safety++
            }
            runCatching { stationDao.cleanupDeadStations() }
            _healthChecked.value = total
            _healthComplete.emit(HealthCheckResult(total, healthy))
        } else {
            _healthComplete.emit(HealthCheckResult(0, 0))
        }
    }

    suspend fun runHealthChecks(batchSize: Int = 200): Int = coroutineScope {
        var healthy = 0
        try {
            val stations = stationDao.getUncheckedStations(batchSize)
            if (stations.isEmpty()) return@coroutineScope 0
            val results = stations.map { s -> async(Dispatchers.IO) { healthSemaphore.withPermit { s to checkStationHealth(s.primaryUrl) } } }.awaitAll()
            healthy = results.count { it.second }
            for ((station, ok) in results) {
                stationDao.updateHealth(station.uuid, ok, if (ok) 0 else station.failCount + 1, System.currentTimeMillis())
            }
            runCatching { stationDao.cleanupDeadStations() }
        } catch (t: Throwable) { Log.e("RadioRepository", "Health: ${t.message}") }
        return@coroutineScope healthy
    }

    private suspend fun checkStationHealth(url: String): Boolean = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext false
        try {
            healthClient.newCall(Request.Builder().url("$PROXY_BASE_URL/health-check?url=${URLEncoder.encode(url, "UTF-8")}").build()).execute().use { it.isSuccessful }
        } catch (t: Throwable) { false }
    }

    suspend fun toggleFavorite(uuid: String, currentStatus: Boolean) = stationDao.updateFavoriteStatus(uuid, !currentStatus)

    fun wrapProxy(rawUrl: String): String {
        if (rawUrl.isBlank()) return ""
        if (rawUrl.contains("radio-joo-proxy")) return rawUrl
        return try { "$PROXY_BASE_URL/?url=" + URLEncoder.encode(rawUrl, "UTF-8") } catch (t: Throwable) { "$PROXY_BASE_URL/?url=" + rawUrl }
    }

    private fun parseStations(arr: org.json.JSONArray): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val uuid = o.optString("uuid"); val url = o.optString("primaryUrl")
                if (uuid.isNotBlank() && url.isNotBlank()) {
                    list.add(StationEntity(uuid=uuid, name=o.optString("name"), primaryUrl=url, fallbackUrl=o.optString("fallbackUrl",""), category=o.optString("category","MUSIC"), countryCode=o.optString("countryCode",""), bitrate=o.optInt("bitrate",64), popularity=o.optInt("popularity",0), lastUpdated=System.currentTimeMillis()))
                }
            } catch (t: Throwable) {}
        }
        return list
    }
}

