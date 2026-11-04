package com.example.radiojooo.data.repository

import android.util.Log
import com.example.radiojooo.data.local.StationDao
import com.example.radiojooo.data.local.TrustedStations
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

class RadioRepository(private val stationDao: StationDao, cacheDir: File) {

    private val PROXY_BASE_URL = "https://radio-joo-proxy.midoc11112.workers.dev"
    private val STATIONS_ENDPOINT = "$PROXY_BASE_URL/stations"

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val allStations: Flow<List<StationEntity>> = stationDao.getAllStations()

    /**
     * الحل الجذري لمشكلة قلة المحطات:
     * 1) القائمة الثابتة الموثوقة (TrustedStations - 185 محطة، كل URLsها متأكد منها بتاريخ 2026-11-04)
     *    تُحفظ دائماً في قاعدة البيانات حتى لو السيرفر واقع أو رجّع محطات قليلة.
     * 2) نتائج السيرفر (Worker) تُضاف فوق القائمة بدون حذف أي محطة موثوقة (merge بدل replace).
     */
    suspend fun fetchStationsFromServer(): Int = withContext(Dispatchers.IO) {
        // أولاً: ضمان وجود القائمة الموثوقة دائماً (حتى لو فشل السيرفر)
        seedTrustedStations()

        var serverCount = 0
        try {
            Log.d("RadioRepository", "جاري جلب المحطات من السيرفر...")

            val request = Request.Builder()
                .url(STATIONS_ENDPOINT)
                .addHeader("Accept", "application/json")
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e("RadioRepository", "فشل: " + response.code)
            } else {
                val jsonStr = response.body?.string()
                if (jsonStr != null) {
                    val jsonObject = JSONObject(jsonStr)
                    val stationsArray = jsonObject.getJSONArray("stations")
                    serverCount = stationsArray.length()
                    Log.d("RadioRepository", "السيرفر بعت: $serverCount محطة")

                    val serverStations = parseStations(stationsArray)
                    if (serverStations.isNotEmpty()) {
                        mergeStations(serverStations)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("RadioRepository", "خطأ في جلب السيرفر (القائمة الموثوقة محفوظة): " + e.message)
        }

        val total = stationDao.getStationsCount()
        Log.d("RadioRepository", "الإجمالي بعد الدمج: $total محطة")
        return@withContext total
    }

    /**
     * يحفظ القائمة الموثوقة مرة واحدة فقط (يستخدم uuid كمفتاح، فالـ insert IGNORE
     * لن يكرر محطات موجودة ولن يمسبها المفضلة عند المستخدم).
     */
    private suspend fun seedTrustedStations() {
        // insertStationsIgnore يعتمد على uuid كمفتاح — آمن للتكرار ولا يمسح بيانات المستخدم،
        // فنقدر ننفذه بأمان في أي وقت بدون flag file (الحذف اليدوي للملف كان سبب عدم ظهور المحطات).
        val count = stationDao.getStationsCount()
        if (count >= TrustedStations.list.size) return
        Log.d("RadioRepository", "جاري حفظ القائمة الموثوقة (" + TrustedStations.list.size + " محطة)...")
        stationDao.insertStationsIgnore(TrustedStations.list.map { it.copy(lastUpdated = System.currentTimeMillis()) })
        Log.d("RadioRepository", "تم. عدد المحطات الآن: " + stationDao.getStationsCount())
    }

    /** دمج محطات السيرفر مع الموجود بدون حذف — المحطة الموجودة تتحدث بياناتها فقط إذا كانت أجدد/أكثر شعبية */
    private suspend fun mergeStations(newStations: List<StationEntity>) {
        val existing = stationDao.getStationsListDirect().associateBy { it.uuid }
        val toInsert = newStations.mapNotNull { ns ->
            val ex = existing[ns.uuid]
            when {
                ex == null -> ns
                ns.popularity > ex.popularity || ex.lastUrlWorks == false -> ns.copy(isFavorite = ex.isFavorite)
                else -> null
            }
        }
        if (toInsert.isNotEmpty()) {
            stationDao.insertStationsIgnore(toInsert)
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

    private suspend fun saveStations(stations: List<StationEntity>) {
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

        stationDao.deleteAllStations()
        stationDao.insertStations(updatedStations)
    }

    suspend fun checkForUpdates(): Boolean = withContext(Dispatchers.IO) {
        try {
            val lastUpdate = stationDao.getLastUpdateTime()
            val currentTime = System.currentTimeMillis()
            val twoHours = 2 * 60 * 60 * 1000L

            if (lastUpdate == null || (currentTime - lastUpdate) > twoHours) {
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
            // نضمن القائمة الموثوقة دائماً — حتى لو قاعدة البيانات فيها محطات قليلة من نسخة قديمة
            seedTrustedStations()
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
