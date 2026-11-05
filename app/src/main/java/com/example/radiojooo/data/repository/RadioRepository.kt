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
import org.json.JSONArray
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

    private val _healthChecked = MutableStateFlow(0)
    val healthChecked: StateFlow<Int> = _healthChecked.asStateFlow()
    private val _healthTotal = MutableStateFlow(0)
    val healthTotal: StateFlow<Int> = _healthTotal.asStateFlow()
    private val _healthComplete = MutableSharedFlow<HealthCheckResult>(replay = 1, extraBufferCapacity = 1)
    val healthComplete: SharedFlow<HealthCheckResult> = _healthComplete.asSharedFlow()

    private val apiClient = OkHttpClient.Builder().connectTimeout(60, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val healthClient = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).followRedirects(true).build()
    private val healthSemaphore = Semaphore(200)
    private val dbMutex = Mutex()

    val allStations: Flow<List<StationEntity>> = stationDao.getVisibleStations()

    // ًں§  ط§ظ„طھط¹ط§ظپظٹ ط§ظ„ط°ظƒظٹ
    suspend fun neuralHeal(station: StationEntity): String? = withContext(Dispatchers.IO) {
        try {
            Log.d("NeuralHeal", "ط¨ط¯ط، ط¹ظ…ظ„ظٹط© ط§ظ„طھط¹ط§ظپظٹ ظ„ظ„ظ…ط­ط·ط©: ${station.name}")
            val url = "$PROXY_BASE_URL/heal?uuid=${URLEncoder.encode(station.uuid, "UTF-8")}&name=${URLEncoder.encode(station.name, "UTF-8")}&country=${station.countryCode}&category=${station.category}&oldUrl=${URLEncoder.encode(station.primaryUrl, "UTF-8")}"
            val req = Request.Builder().url(url).build()
            apiClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    if (json.optBoolean("success", false)) {
                        val newUrl = json.optString("newUrl")
                        val confidence = json.optDouble("confidence", 0.0)
                        Log.d("NeuralHeal", "âœ… ظ†ط¬ط­ ط§ظ„طھط¹ط§ظپظٹ: $newUrl (ط«ظ‚ط©: $confidence)")
                        stationDao.updateStationUrl(station.uuid, newUrl)
                        return@withContext newUrl
                    }
                }
            }
        } catch (e: Exception) { Log.e("NeuralHeal", "ظپط´ظ„: ${e.message}") }
        return@withContext null
    }

    // ًںژ“ ط¥ط±ط³ط§ظ„ طھظ‚ط±ظٹط± ط§ظ„ظ†ط¬ط§ط­/ط§ظ„ظپط´ظ„ ظ„ظ„طھط¹ظ„ظ…
    suspend fun sendFeedback(uuid: String, url: String, success: Boolean, errorType: String = "none") = withContext(Dispatchers.IO) {
        try {
            val fbUrl = "$PROXY_BASE_URL/ai/feedback?uuid=${URLEncoder.encode(uuid, "UTF-8")}&url=${URLEncoder.encode(url, "UTF-8")}&success=$success"
            apiClient.newCall(Request.Builder().url(fbUrl).build()).execute().close()
        } catch (_: Exception) {}
    }

    // ًں”® ط§ظ„طھظ†ط¨ط¤ ط¨ط§ظ„ظ…ط­ط·ط§طھ ط§ظ„ظ…ط¹ط±ط¶ط© ظ„ظ„ط®ط·ط±
    suspend fun predictRisks(): List<String> = withContext(Dispatchers.IO) {
        try {
            val all = stationDao.getVisibleStationsSnapshot()
            if (all.isEmpty()) return@withContext emptyList()
            val json = JSONArray().apply {
                all.take(50).forEach { put(JSONObject().apply { put("uuid", it.uuid); put("name", it.name); }) }
            }
            val url = "$PROXY_BASE_URL/ai/predict?stations=${URLEncoder.encode(json.toString(), "UTF-8")}"
            apiClient.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: return@withContext emptyList()
                    val predictions = JSONObject(body).optJSONArray("predictions") ?: return@withContext emptyList()
                    val risky = mutableListOf<String>()
                    for (i in 0 until predictions.length()) {
                        risky.add(predictions.getJSONObject(i).optString("uuid"))
                    }
                    return@withContext risky
                }
            }
        } catch (e: Exception) { Log.e("Predict", e.message ?: "") }
        return@withContext emptyList()
    }

    suspend fun fetchStationsFromServer(): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            val req = Request.Builder().url("$PROXY_BASE_URL/stations").build()
            apiClient.newCall(req).execute().use { response ->
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "")
                    val arr = json.getJSONArray("stations")
                    count = arr.length()
                    val stations = parseStations(arr)
                    if (stations.isNotEmpty()) stationDao.insertStations(stations)
                }
            }
        } catch (e: Exception) { Log.e("RadioRepository", "Fetch error: ${e.message}") }
        return@withContext stationDao.getTotalCount()
    }

    suspend fun ensureInitialDataLoaded() {
        dbMutex.withLock {
            _healthTotal.value = 0
            _healthChecked.value = 0
            fetchStationsFromServer()
        }
        delay(500)
        performHealthChecks()
        // ًں§  ط¨ط¹ط¯ ط§ظ„ظپط­طµطŒ ظ†ط´ط؛ظ„ ط§ظ„طھظ†ط¨ط¤ ظˆط§ظ„ظپط­طµ ط§ظ„ط§ط³طھط¨ط§ظ‚ظٹ ظ„ظ„ظ…ط­ط·ط§طھ ط¹ط§ظ„ظٹط© ط§ظ„ظ…ط®ط§ط·ط±
        proactiveHeal()
    }

    suspend fun forceRefreshAll() {
        dbMutex.withLock {
            stationDao.resetAllHealthStatus()
            fetchStationsFromServer()
        }
        delay(500)
        performHealthChecks()
    }

    // ًںژ¯ ط§ظ„طھط¹ط§ظپظٹ ط§ظ„ط§ط³طھط¨ط§ظ‚ظٹ ظ„ظ„ظ…ط­ط·ط§طھ ط§ظ„ظ…ط¹ط±ط¶ط© ظ„ظ„ط®ط·ط±
    private suspend fun proactiveHeal() = withContext(Dispatchers.IO) {
        val riskyUuids = predictRisks()
        for (uuid in riskyUuids.take(5)) {
            val station = stationDao.getStationById(uuid) ?: continue
            if (station.failCount > 2) continue // ظ…ظ‡ظˆ ظ…ط´ ط®ط·ظٹط± ظ„ط³ظ‡
            Log.d("ProactiveHeal", "طھط¹ط§ظپظچ ط§ط³طھط¨ط§ظ‚ظٹ: ${station.name}")
            neuralHeal(station)
        }
    }

    private suspend fun performHealthChecks() {
        val unchecked = stationDao.getUncheckedCount()
        val total = unchecked
        _healthTotal.value = total
        _healthChecked.value = 0
        if (total > 0) {
            var healthy = 0
            var safety = 0
            while (safety < 200 && stationDao.getUncheckedCount() > 0) {
                healthy += runHealthChecks(200)
                _healthChecked.value = minOf(_healthChecked.value + 200, total)
                safety++
            }
            stationDao.cleanupDeadStations()
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
            val results = stations.map { s ->
                async(Dispatchers.IO) { healthSemaphore.withPermit { s to checkStationHealth(s.primaryUrl) } }
            }.awaitAll()
            healthy = results.count { it.second }
            for ((station, ok) in results) {
                stationDao.updateHealth(station.uuid, ok, if (ok) 0 else station.failCount + 1, System.currentTimeMillis())
            }
            stationDao.cleanupDeadStations()
        } catch (e: Exception) { Log.e("RadioRepository", "HealthCheck: ${e.message}") }
        return@coroutineScope healthy
    }

    private suspend fun checkStationHealth(url: String): Boolean = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext false
        try {
            val healthUrl = "$PROXY_BASE_URL/health-check?url=${URLEncoder.encode(url, "UTF-8")}"
            healthClient.newCall(Request.Builder().url(healthUrl).build()).execute().use { it.isSuccessful }
        } catch (e: Exception) { false }
    }

    suspend fun toggleFavorite(uuid: String, currentStatus: Boolean) = stationDao.updateFavoriteStatus(uuid, !currentStatus)

    fun wrapProxy(rawUrl: String): String {
        if (rawUrl.isBlank()) return ""
        if (rawUrl.contains("radio-joo-proxy")) return rawUrl
        return try { "$PROXY_BASE_URL/?url=" + URLEncoder.encode(rawUrl, "UTF-8") } catch (e: Exception) { "$PROXY_BASE_URL/?url=" + rawUrl }
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
            } catch (e: Exception) {}
        }
        return list
    }
}

// Extension ظ„ظ€ StationDao (ظپظٹ ظ†ظپط³ ط§ظ„ظ…ظ„ظپ ظƒظ€ top-level)
suspend fun StationDao.getVisibleStationsSnapshot(): List<StationEntity> {
    // ظ†ط­طھط§ط¬ ط¯ط§ظ„ط© snapshot - ط³ط£ط¶ظٹظپظ‡ط§ ظپظٹ DAO
    return getAllStationsSnapshot()
}