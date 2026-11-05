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

class RadioRepository(
    private val context: Context,
    stationDaoParam: StationDao? = null,
    cacheDir: File? = null
) {
    private val stationDao: StationDao = stationDaoParam
        ?: AppDatabase.getDatabase(context).stationDao()

    private val PROXY_BASE_URL = "https://radio-joo-proxy.midoc11112.workers.dev"
    private val STATIONS_ENDPOINT = "$PROXY_BASE_URL/stations"

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

    /** Flow التطبيق يستهلكه — يفلتر تلقائيًا المحطات الميتة */
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

    /**
     * فحص صحة المحطات غير المفحوصة + إعادة فحص المحطات القديمة (>24h)
     * يعمل 6 فحوص متوازية، timeout 4s لكل فحص
     * إذا فشلت كل الفحوص في دفعة (>=5) → يعتبرها مشكلة شبكة ولا يحدّث DB
     */
    suspend fun runHealthChecks(batchSize: Int = 30) = coroutineScope {
        try {
            val unchecked = stationDao.getUncheckedStations(batchSize)
            val staleThreshold = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            val stale = stationDao.getStaleVerifiedStations(staleThreshold, batchSize / 2)

            val toCheck = (unchecked + stale).distinctBy { it.uuid }
            if (toCheck.isEmpty()) return@coroutineScope

            Log.d("RadioRepository", "فحص صحة ${toCheck.size} محطة...")

            val results = toCheck.map { station ->
                async(Dispatchers.IO) {
                    healthSemaphore.withPermit {
                        station to checkStationHealth(station.primaryUrl)
                    }
                }
            }.awaitAll()

            val healthyCount = results.count { it.second }
            if (healthyCount == 0 && results.size >= 5) {
                Log.w("RadioRepository", "كل الفحوص فشلت (${results.size}) - تجاهل التحديث (شك في مشكلة شبكة)")
                return@coroutineScope
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
        // 416 = Range Not Satisfiable → ملف صوتي صغير، يعني السيرفر حي
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
            val count = stationDao.getTotalCount()
            Log.d("RadioRepository", "عدد المحطات الحالي: $count")
            if (count < 50) {
                Log.d("RadioRepository", "عدد المحطات قليل - جاري جلب من السيرفر...")
                fetchStationsFromServer()
            }

            // فحص كامل لكل المحطات غير المفحوصة (يستمر حتى النهاية)
            Log.d("RadioRepository", "بدء الفحص الشامل لصحة المحطات...")
            var safety = 0
            while (safety < 20) {
                val unchecked = stationDao.getUncheckedCount()
                if (unchecked == 0) break
                runHealthChecks(batchSize = 30)
                safety++
            }

            // تنظيف المحطات الميتة (فشلت 5+ مرات)
            try { stationDao.cleanupDeadStations() } catch (_: Exception) {}
            Log.d("RadioRepository", "اكتمل الفحص. المحطات في DB: ${stationDao.getTotalCount()}")
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
                val primaryUrl = obj
