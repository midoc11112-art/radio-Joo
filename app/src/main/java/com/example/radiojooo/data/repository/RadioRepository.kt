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

    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val healthSemaphore = Semaphore(25)
    private val dbMutex = Mutex()

    private val MAX_CHECKS_PER_SESSION = 150
    private val BATCH_SIZE = 25
    private val INTER_BATCH_DELAY_MS = 200L

    private enum class HealthStatus { HEALTHY, UNHEALTHY, UNKNOWN }

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
                    if (stations.isNotEmpty()) {
                        stations.chunked(500).forEach { chunk -> stationDao.insertStations(chunk) }
                    }
                }
            }
        } catch (t: Throwable) { Log.e("RadioRepository", "Fetch: ${t.message}") }
        return@withContext stationDao.getTotalCount()
    }

    suspend fun ensureInitialDataLoaded() {
        try {
            dbMutex.withLock {
                _healthTotal.value = 0; _healthChecked.value = 0
                fetchStationsFromServer()
            }
            delay(500)
            performHealthChecks(MAX_CHECKS_PER_SESSION)
        } catch (t: Throwable) { Log.e("RadioRepository", "Init: ${t.message}") }
    }

    suspend fun forceRefreshAll() {
        try {
            dbMutex.withLock {
                stationDao.resetAllHealthStatus()
                fetchStationsFromServer()
            }
            delay(500)
            performHealthChecks(300)
        } catch (t: Throwable) { Log.e("RadioRepository", "Refresh: ${t.message}") }
    }

    private suspend fun performHealthChecks(maxPerSession: Int = MAX_CHECKS_PER_SESSION) {
        val unchecked = stationDao.getUncheckedCount()
        val target = minOf(unchecked, maxPerSession)
        _healthTotal.value = target; _healthChecked.value = 0
        if (unchecked <= 0) { _healthComplete.emit(HealthCheckResult(0, 0)); return }

        var healthy = 0; var checked = 0; var safety = 0
        val maxBatches = (target + BATCH_SIZE - 1) / BATCH_SIZE
        while (safety < maxBatches && checked < maxPerSession && stationDao.getUncheckedCount() > 0) {
            val remaining = maxPerSession - checked
            val thisBatch = minOf(BATCH_SIZE, remaining)
            val (h, c) = runHealthChecksWithCount(thisBatch)
            if (c == 0) { Log.w("RadioRepository", "Health: no progress, likely 429"); break }
            healthy += h; checked += c; _healthChecked.value = checked; safety++
            if (checked < maxPerSession) delay(INTER_BATCH_DELAY_MS)
        }
        runCatching { stationDao.cleanupDeadStations() }
        _healthChecked.value = checked
        _healthComplete.emit(HealthCheckResult(checked, healthy))
    }

    suspend fun runHealthChecks(batchSize: Int = BATCH_SIZE): Int = runHealthChecksWithCount(batchSize).first

    private suspend fun runHealthChecksWithCount(batchSize: Int): Pair<Int, Int> = coroutineScope {
        var healthy = 0; var checked = 0
        try {
            val stations = stationDao.getUncheckedStations(batchSize)
            if (stations.isEmpty()) return@coroutineScope 0 to 0
            val results = stations.map { s -> async(Dispatchers.IO) { healthSemaphore.withPermit { s to checkStationHealth(s.primaryUrl) } } }.awaitAll()
            for ((station, status) in results) {
                when (status) {
                    HealthStatus.HEALTHY -> { healthy++; checked++; stationDao.updateHealth(station.uuid, true, 0, System.currentTimeMillis()) }
                    HealthStatus.UNHEALTHY -> { checked++; stationDao.updateHealth(station.uuid, false, station.failCount + 1, System.currentTimeMillis()) }
                    HealthStatus.UNKNOWN -> { /* لا نلمس */ }
                }
            }
            runCatching { stationDao.cleanupDeadStations() }
        } catch (t: Throwable) { Log.e("RadioRepository", "Health: ${t.message}") }
        return@coroutineScope healthy to checked
    }

    private suspend fun checkStationHealth(url: String): HealthStatus = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext HealthStatus.UNHEALTHY
        try {
            healthClient.newCall(Request.Builder().url("$PROXY_BASE_URL/health-check?url=${URLEncoder.encode(url, "UTF-8")}").build()).execute().use { resp ->
                val body = resp.body?.string()?.trim() ?: ""
                when {
                    body == "OK" -> HealthStatus.HEALTHY
                    body == "FAIL" -> HealthStatus.UNHEALTHY
                    resp.code == 429 || resp.code in 500..599 -> HealthStatus.UNKNOWN
                    else -> HealthStatus.UNKNOWN
                }
            }
        } catch (t: Throwable) { HealthStatus.UNKNOWN }
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
                    list.add(StationEntity(uuid=uuid, name=o.optString("name"), primaryUrl=url, fallbackUrl=o.optString("fallbackUrl",""), category=o.optString("category","MUSIC"), countryCode=o.optString("countryCode",""), bitrate=o.optInt("bitrate",0), popularity=o.optInt("popularity",0), lastUpdated=System.currentTimeMillis()))
                }
            } catch (t: Throwable) {}
        }
        return list
    }
}