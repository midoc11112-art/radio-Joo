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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val stationDao: StationDao = stationDaoParam
        ?: AppDatabase.getDatabase(context).stationDao()

    private val PROXY_BASE_URL = "https://radio-joo-proxy.midoc11112.workers.dev"
    private val STATIONS_ENDPOINT = "$PROXY_BASE_URL/stations"

    // 🆕 تدفقات التقدم
    private val _healthChecked = MutableStateFlow(0)
    val healthChecked: StateFlow<Int> = _healthChecked.asStateFlow()

    private val _healthTotal = MutableStateFlow(0)
    val healthTotal: StateFlow<Int> = _healthTotal.asStateFlow()

    private val _healthComplete = MutableSharedFlow<HealthCheckResult>(replay = 1, extraBufferCapacity = 1)
    val healthComplete: SharedFlow<HealthCheckResult> = _healthComplete.asSharedFlow()

    private val apiClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private val healthClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val healthSemaphore = Semaphore(6)

    val allStations: Flow<List<StationEntity>> = stationDao.getVisibleStations()

    suspend fun fetchStationsFromServer(): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            Log.d("RadioRepository", "جاري جلب المحطات من السيرفر...")
            val request = Request.Builder()
                .url(STATIONS_ENDPOINT)
                .addHeader("Accept", "application/json")
                .build()

            val response = apiClient.newCall(request).execute()
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
            }
        } catch (e: Exception) {
            Log.e("RadioRepository", "خطأ في جلب السيرفر: " + e.message)
        }

        val total = stationDao.getTotalCount()
        Log.d("RadioRepository", "الإجمالي في قاعدة البيانات: $total")
        return@withContext total
    }

    suspend fun runHealthChecks(batchSize: Int = 30): Int = coroutineScope {
        var healthyCount = 0
        try {
            val unchecked = stationDao.getUncheckedStations(batchSize)
            val staleThreshold = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            val stale = stationDao.getStaleVerifiedStations(staleThreshold, batchSize / 2)

            val toCheck = (unchecked + stale).distinctBy { it.uuid }
            if (toCheck.isEmpty()) return@coroutineScope 0

            Log.d("RadioRepository", "فحص صحة ${toCheck.size} محطة...")

            val results = toCheck.map { station ->
                async(Dispatchers.IO) {
                    healthSemaphore.withPermit {
                        station to checkStationHealth(station.primaryUrl)
                    }
                }
            }.awaitAll()

            healthyCount = results.count { it.second }
            if (healthyCount == 0 && results.size >= 5) {
                Log.w("RadioRepository", "كل الفحوص فشلت (${results.size}) - تجاهل التحديث")
                return@coroutineScope 0
            }

            for ((station, works) in results) {
                val newFailCount = if (works) 0 else station.failCount + 1
                stationDao.updateHealth(
                    station.uuid, works, newFailCount, System.currentTimeMillis()
                )
            }

            Log.d("RadioRepository", "نتيجة الفحص: $healthyCount/${toCheck.size} شغالة")
        } catch (e: Exception) {
            Log.e("RadioRepository", "خطأ في فحص الصحة: ${e.message}")
        }
        return@coroutineScope healthyCount
    }

    private suspend fun checkStationHealth(url: String): Boolean = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext false
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) ExoPlayer/1.6.1")
                .header("Accept", "audio/*, */*")
                .header("Range", "bytes=0-2047")
                .build()

            val response = healthClient.newCall(request).execute()
            val ok = isHealthyResponse(response)
            response.close()
            ok
        } catch (e: Exception) {
            false
        }
    }

    private fun isHealthyResponse(response: Response): Boolean {
        if (response.code == 416) return true
        if (!response.isSuccessful) return false
        val contentType = (response.header("Content-Type") ?: "").lowercase()
        if (contentType.isEmpty()) return true
        return contentType.contains("audio") ||
                contentType.contains("mpeg") ||
                contentType.contains("mpegurl") ||
                contentType.contains("octet-stream") ||
                contentType.contains("ogg") ||
                contentType.contains("aac") ||
                contentType.contains("flac") ||
                contentType.contains("opus")
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext false
    }

    suspend fun ensureInitialDataLoaded() {
        withContext(Dispatchers.IO) {
            try {
                val count = stationDao.getTotalCount()
                Log.d("RadioRepository", "عدد المحطات الحالي: $count")
                if (count < 50) {
                    Log.d("RadioRepository", "عدد المحطات قليل - جاري جلب من السيرفر...")
                    fetchStationsFromServer()
                }

                // حساب العدد الكلي المتوقع فحصه
                val uncheckedAtStart = stationDao.getUncheckedCount()
                val staleThreshold = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
                val staleAtStart = stationDao.getStaleVerifiedStations(staleThreshold, Int.MAX_VALUE).size
                val totalToCheck = uncheckedAtStart + staleAtStart
                _healthTotal.value = totalToCheck
                _healthChecked.value = 0

                Log.d("RadioRepository", "بدء الفحص الشامل لصحة $totalToCheck محطة...")

                var totalHealthy = 0
                var safety = 0
                while (safety < 30) {
                    val unchecked = stationDao.getUncheckedCount()
                    if (unchecked == 0) break
                    val healthyThisBatch = runHealthChecks(batchSize = 30)
                    totalHealthy += healthyThisBatch
                    // تحديث العداد
                    val checkedSoFar = _healthChecked.value + 30
                    _healthChecked.value = minOf(checkedSoFar, totalToCheck)
                    safety++
                }

                try { stationDao.cleanupDeadStations() } catch (_: Exception) {}

                // ضبط العداد النهائي
                _healthChecked.value = totalToCheck

                Log.d("RadioRepository", "اكتمل الفحص. الإجمالي: $totalToCheck، الشغالة: $totalHealthy")

                // 🆕 إطلاق حدث اكتمال الفحص
                _healthComplete.emit(HealthCheckResult(
                    total = totalToCheck,
                    healthy = totalHealthy
                ))
            } catch (e: Exception) {
                Log.e("RadioRepository", "خطأ في ensureInitialDataLoaded: ${e.message}")
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
