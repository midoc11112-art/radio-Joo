package com.example.radiojooo.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.radiojooo.data.local.AppDatabase
import com.example.radiojooo.data.local.StationDao
import com.example.radiojooo.data.model.StationEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.Calendar
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
    private val prefs: SharedPreferences by lazy { context.getSharedPreferences("radio_joo_prefs", Context.MODE_PRIVATE) }

    private val _healthChecked = MutableStateFlow(0)
    val healthChecked: StateFlow<Int> = _healthChecked.asStateFlow()
    private val _healthTotal = MutableStateFlow(0)
    val healthTotal: StateFlow<Int> = _healthTotal.asStateFlow()
    private val _healthComplete = MutableSharedFlow<HealthCheckResult>(replay = 1, extraBufferCapacity = 1)
    val healthComplete: SharedFlow<HealthCheckResult> = _healthComplete.asSharedFlow()

    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    private val healClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(12, TimeUnit.SECONDS).followRedirects(true).build()

    private val webSearchClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS).followRedirects(true).build()

    private val healthClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).followRedirects(true).build()

    private val healthSemaphore = Semaphore(50)
    private val dbMutex = Mutex()

    private val MAX_CHECKS_PER_SESSION = 2000
    private val BATCH_SIZE = 100
    private val INTER_BATCH_DELAY_MS = 50L

    private enum class HealthStatus { HEALTHY, UNHEALTHY, UNKNOWN }

    val allStations: kotlinx.coroutines.flow.Flow<List<StationEntity>> = stationDao.getVisibleStations()
    private var healthCheckJob: Job? = null

    suspend fun neuralHeal(station: StationEntity): HealResult? = withContext(Dispatchers.IO) {
        // PHASE 0: Try cloud backup first
        try {
            val cloudReq = "$PROXY_BASE_URL/get-saved-url?uuid=${URLEncoder.encode(station.uuid, "UTF-8")}"
            healClient.newCall(Request.Builder().url(cloudReq).build()).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: ""
                    val json = JSONObject(body)
                    if (json.optBoolean("success", false)) {
                        val savedUrl = json.optString("url")
                        if (savedUrl.isNotBlank() && savedUrl.startsWith("http")) {
                            if (checkUrlAlive(savedUrl)) {
                                persistHealedUrl(station.uuid, savedUrl)
                                Log.d("HealAI", "✓ Cloud backup used: ${station.name} -> $savedUrl")
                                return@withContext HealResult(savedUrl, station.name + " (cloud)")
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.d("HealAI", "Cloud backup check failed: ${t.message}")
        }

        // PHASE 1: Worker heal
        try {
            val url = "$PROXY_BASE_URL/heal?uuid=${URLEncoder.encode(station.uuid, "UTF-8")}&name=${URLEncoder.encode(station.name, "UTF-8")}&country=${station.countryCode}&category=${station.category}"
            healClient.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: return@withContext null
                    val json = JSONObject(body)

                    if (json.optBoolean("success", false)) {
                        val newUrl = json.optString("newUrl")
                        val matchedName = json.optString("matchedName", station.name)
                        if (newUrl.isNotBlank() && newUrl.startsWith("http")) {
                            persistHealedUrl(station.uuid, newUrl)
                            saveUrlToCloud(station.uuid, newUrl, station.primaryUrl)
                            Log.d("HealAI", "✓ Worker healed: ${station.name} -> $newUrl")
                            return@withContext HealResult(newUrl, matchedName)
                        }
                    }

                    // PHASE 2: Web search if signaled
                    if (json.optBoolean("needsWebSearch", false)) {
                        Log.d("HealAI", "Worker signaled web search for: ${station.name}")
                        val webUrl = webSearchForStream(station)
                        if (webUrl != null && checkUrlAlive(webUrl)) {
                            persistHealedUrl(station.uuid, webUrl)
                            saveUrlToCloud(station.uuid, webUrl, station.primaryUrl)
                            Log.d("HealAI", "✓ Web search found: ${station.name} -> $webUrl")
                            return@withContext HealResult(webUrl, station.name + " (web)")
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("HealAI", "Heal request failed: ${t.message}", t)
        }
        return@withContext null
    }

    private suspend fun persistHealedUrl(uuid: String, newUrl: String) {
        try {
            stationDao.updateStationUrl(uuid, newUrl)
            Log.d("HealAI", "✓ DB persisted: $uuid -> $newUrl")
        } catch (e: Throwable) {
            Log.e("HealAI", "✗ DB persist failed: ${e.message}", e)
        }
        try {
            prefs.edit().putString("healed_url_$uuid", newUrl).apply()
            Log.d("HealAI", "✓ Prefs persisted: $uuid")
        } catch (e: Throwable) {
            Log.e("HealAI", "✗ Prefs persist failed: ${e.message}", e)
        }
    }

    private suspend fun saveUrlToCloud(uuid: String, newUrl: String, oldUrl: String) = withContext(Dispatchers.IO) {
        try {
            val reqUrl = "$PROXY_BASE_URL/save-url?uuid=${URLEncoder.encode(uuid, "UTF-8")}&newUrl=${URLEncoder.encode(newUrl, "UTF-8")}&oldUrl=${URLEncoder.encode(oldUrl, "UTF-8")}"
            healClient.newCall(Request.Builder().url(reqUrl).build()).execute().close()
            Log.d("HealAI", "✓ Cloud saved: $uuid")
        } catch (_: Throwable) {}
    }

    private suspend fun checkUrlAlive(url: String): Boolean = withContext(Dispatchers.IO) {
        if (url.isBlank() || !url.startsWith("http")) return@withContext false
        try {
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").header("Range", "bytes=0-1024").build()
            healthClient.newCall(req).execute().use { res ->
                if (res.isSuccessful || res.code == 206) {
                    val ct = res.header("Content-Type")?.lowercase() ?: ""
                    if (!ct.contains("text/html") && !ct.contains("application/json")) return@withContext true
                }
            }
        } catch (_: Throwable) {}
        false
    }

    private suspend fun webSearchForStream(station: StationEntity): String? = withContext(Dispatchers.IO) {
        val queries = listOf(
            "\"${station.name}\" radio stream mp3",
            "${station.name} listen live",
            "${station.name} ${station.countryCode} radio"
        )

        for (q in queries) {
            try {
                val searchUrl = "https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8")
                val req = Request.Builder().url(searchUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/124.0.0.0")
                    .build()
                webSearchClient.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val html = res.body?.string() ?: ""
                        val urls = extractStreamUrls(html)
                        for (u in urls) {
                            if (checkUrlAlive(u)) return@withContext u
                        }
                    }
                }
            } catch (_: Throwable) {}
            delay(400)
        }

        try {
            val myTunerUrl = "https://mytuner-radio.com/search/?q=" + URLEncoder.encode(station.name, "UTF-8")
            val req = Request.Builder().url(myTunerUrl).header("User-Agent", "Mozilla/5.0").build()
            webSearchClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val html = res.body?.string() ?: ""
                    val urls = extractStreamUrls(html)
                    for (u in urls) if (checkUrlAlive(u)) return@withContext u
                }
            }
        } catch (_: Throwable) {}

        try {
            val streemaUrl = "https://streema.com/search?q=" + URLEncoder.encode(station.name, "UTF-8")
            val req = Request.Builder().url(streemaUrl).header("User-Agent", "Mozilla/5.0").build()
            webSearchClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val html = res.body?.string() ?: ""
                    val urls = extractStreamUrls(html)
                    for (u in urls) if (checkUrlAlive(u)) return@withContext u
                }
            }
        } catch (_: Throwable) {}

        null
    }

    private fun extractStreamUrls(html: String): List<String> {
        val urls = mutableSetOf<String>()
        val streamRegex = Regex("""https?://[^\s"'<>\\]+\.(?:mp3|aac|ogg|m3u8|m3u)(?:\?[^\s"'<>\\]*)?""")
        streamRegex.findAll(html).forEach { urls.add(it.value.replace("\\/", "/")) }
        val patternRegex = Regex("""https?://[^\s"'<>\\]*(?:stream|listen|audio|icecast|shoutcast)[^\s"'<>\\]*""")
        patternRegex.findAll(html).take(30).forEach {
            val cleaned = it.value.replace("\\/", "/").trimEnd('.', ',', ';', ')', ']', '}')
            if (cleaned.length > 20) urls.add(cleaned)
        }
        return urls.filter { u ->
            !u.contains("google.com") && !u.contains("youtube.com") &&
            !u.contains("facebook.com") && !u.contains("twitter.com") &&
            !u.contains("wikipedia.org") && !u.contains("amazon.com") &&
            u.length < 500
        }.toList()
    }

    suspend fun healBatch(limit: Int = 30): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var healed = 0
        var failed = 0
        try {
            val stations = stationDao.getStationsNeedingHeal(limit)
            Log.d("HealBatch", "Starting batch of ${stations.size} stations")
            for (s in stations) {
                try {
                    val result = neuralHeal(s)
                    if (result != null) {
                        healed++
                        Log.d("HealBatch", "✓ ${s.name} -> ${result.url}")
                    } else {
                        failed++
                        Log.d("HealBatch", "✗ ${s.name}")
                        try { stationDao.updateHealth(s.uuid, false, s.failCount + 1, System.currentTimeMillis()) } catch (_: Throwable) {}
                    }
                    delay(300)
                } catch (_: Throwable) { failed++ }
            }
        } catch (t: Throwable) {
            Log.e("HealBatch", "Batch failed: ${t.message}", t)
        }
        return@withContext healed to failed
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
                        try { stationDao.syncPriorities(stations) } catch (t: Throwable) { Log.e("RadioRepository", "syncPriorities: ${t.message}") }

                        try {
                            for (s in stations) {
                                val backupUrl = prefs.getString("healed_url_${s.uuid}", null)
                                if (!backupUrl.isNullOrBlank() && backupUrl != s.primaryUrl) {
                                    try {
                                        stationDao.updateStationUrl(s.uuid, backupUrl)
                                        Log.d("RadioRepository", "✓ Restored from backup: ${s.name} -> $backupUrl")
                                    } catch (_: Throwable) {}
                                }
                            }
                        } catch (_: Throwable) {}
                    }
                }
            }
        } catch (t: Throwable) { Log.e("RadioRepository", "Fetch: ${t.message}") }
        return@withContext stationDao.getTotalCount()
    }

    suspend fun performSmartHealthCheck() {
        val lastScanTime = stationDao.getLastFullScanTime() ?: 0L
        val isSameDay = isSameDay(lastScanTime)
        if (!isSameDay) { dbMutex.withLock { stationDao.resetAllHealthStatus() } }
        performHealthChecks(MAX_CHECKS_PER_SESSION)
    }

    private fun isSameDay(timestamp: Long): Boolean {
        if (timestamp == 0L) return false
        val cal1 = Calendar.getInstance().apply { timeInMillis = timestamp }
        val cal2 = Calendar.getInstance()
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) && cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    suspend fun ensureInitialDataLoaded() {
        try {
            fetchStationsFromServer()
            delay(500)
            performSmartHealthCheck()
        } catch (t: Throwable) { Log.e("RadioRepository", "Init: ${t.message}") }
    }

    suspend fun forceRefreshAll(): Int {
        try {
            fetchStationsFromServer()
            delay(500)
            performSmartHealthCheck()
        } catch (t: Throwable) { Log.e("RadioRepository", "Refresh: ${t.message}") }
        return stationDao.getTotalCount()
    }

    fun stopHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = null
        _healthChecked.value = 0
        _healthTotal.value = 0
    }

    suspend fun runHealthChecks(batchSize: Int = BATCH_SIZE): Int = withContext(Dispatchers.IO) {
        return@withContext runHealthChecksWithCount(batchSize).first
    }

    private suspend fun performHealthChecks(maxPerSession: Int = MAX_CHECKS_PER_SESSION) {
        healthCheckJob?.cancel()
        healthCheckJob = CoroutineScope(Dispatchers.IO).launch {
            val unchecked = stationDao.getUncheckedCount()
            val target = minOf(unchecked, maxPerSession)
            _healthTotal.value = target
            _healthChecked.value = 0
            if (unchecked <= 0) {
                _healthComplete.emit(HealthCheckResult(0, 0))
                return@launch
            }
            var healthy = 0; var checked = 0; var safety = 0
            val maxBatches = (target + BATCH_SIZE - 1) / BATCH_SIZE
            while (safety < maxBatches && checked < maxPerSession && stationDao.getUncheckedCount() > 0) {
                if (!isActive) break
                val remaining = maxPerSession - checked
                val thisBatch = minOf(BATCH_SIZE, remaining)
                val (h, c) = runHealthChecksWithCount(thisBatch)
                if (c == 0) break
                healthy += h; checked += c; _healthChecked.value = checked; safety++
                if (checked < maxPerSession) delay(INTER_BATCH_DELAY_MS)
            }
            runCatching { stationDao.cleanupDeadStations() }
            _healthChecked.value = checked
            _healthComplete.emit(HealthCheckResult(checked, healthy))
        }
    }

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
                    HealthStatus.UNKNOWN -> { }
                }
            }
        } catch (t: Throwable) { if (t is CancellationException) throw t }
        return@coroutineScope healthy to checked
    }

    private suspend fun checkStationHealth(url: String): HealthStatus = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext HealthStatus.UNHEALTHY
        try {
            healthClient.newCall(Request.Builder().url("$PROXY_BASE_URL/health-check?url=${URLEncoder.encode(url, "UTF-8")}").build()).execute().use { resp ->
                val body = resp.body?.string()?.trim() ?: ""
                if (body == "FAIL") return@withContext HealthStatus.UNHEALTHY
                if (body == "OK") return@withContext HealthStatus.HEALTHY
                val actualBitrate = body.toIntOrNull()
                if (actualBitrate != null) return@withContext HealthStatus.HEALTHY
                HealthStatus.UNKNOWN
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            HealthStatus.UNKNOWN
        }
    }

    suspend fun toggleFavorite(uuid: String, currentStatus: Boolean) = stationDao.updateFavoriteStatus(uuid, !currentStatus)
    fun wrapProxy(rawUrl: String): String = if (rawUrl.isBlank()) "" else try { "$PROXY_BASE_URL/?url=" + URLEncoder.encode(rawUrl, "UTF-8") } catch (t: Throwable) { "$PROXY_BASE_URL/?url=" + rawUrl }

    private fun parseStations(arr: org.json.JSONArray): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val uuid = o.optString("uuid"); val url = o.optString("primaryUrl")
                if (uuid.isNotBlank() && url.isNotBlank()) {
                    list.add(StationEntity(
                        uuid=uuid,
                        name=o.optString("name"),
                        primaryUrl=url,
                        fallbackUrl=o.optString("fallbackUrl",""),
                        category=o.optString("category","MUSIC"),
                        countryCode=o.optString("countryCode",""),
                        bitrate=o.optInt("bitrate                        popularity=o.optInt("popularity",0),
                        priority=o.optInt("priority",0),
                        lastUpdated=System.currentTimeMillis()
                    ))
                }
            } catch (t: Throwable) {}
        }
        return list
    }
}