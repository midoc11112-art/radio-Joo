package com.example.radiojooo.data.repository

import android.content.Context
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

class RadioRepository(
    private val context: android.content.Context,
    private val stationDao: StationDao,
    cacheDir: File = File(context.cacheDir, "radio_cache")
) {

    companion object {
        private const val MIN_SERVER_POPULARITY = 100
    }

    private val PROXY_BASE_URL = "https://radio-joo-proxy.midoc11112.workers.dev"
    private val STATIONS_ENDPOINT = "$PROXY_BASE_URL/stations"

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val allStations: Flow<List<StationEntity>> = stationDao.getAllStations()

    suspend fun fetchStationsFromServer(): Int = withContext(Dispatchers.IO) {
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

    private suspend fun seedTrustedStations() {
        val prefs = context.getSharedPreferences("radio_joo_prefs", Context.MODE_PRIVATE)
        val appliedVersion = prefs.getString("trusted_list_version", "") ?: ""

        if (appliedVersion != TrustedStations.LIST_VERSION) {
            val seededJson = prefs.getString("seeded_uuids", null)
            val seededUuids = HashSet<String>()
            if (seededJson != null) {
                val arr = org.json.JSONArray(seededJson)
                for (i in 0 until arr.length()) seededUuids.add(arr.getString(i))
            }
            val all = stationDao.getStationsListDirect()
            val toDelete = all.filter { st ->
                !st.isFavorite && (
                    st.uuid in seededUuids ||
                    st.category.isBlank() ||
                    st.uuid.startsWith("v2-") ||
                    st.uuid.startsWith("x-") ||
                    st.uuid.startsWith("manual-")
                )
            }.map { it.uuid }
            if (toDelete.isNotEmpty()) stationDao.deleteByUuids(toDelete)
            prefs.edit()
                .putString("trusted_list_version", TrustedStations.LIST_VERSION)
                .remove("seeded_uuids")
                .apply()
            Log.d("RadioRepository", "مهاجرة لقائمة المحطات الموثوقة إصدار " + TrustedStations.LIST_VERSION + " — حُذفت " + toDelete.size + " محطة قديمة")
        }

        val seededKey = org.json.JSONArray(TrustedStations.list.map { it.uuid })
        prefs.edit().putString("seeded_uuids", seededKey.toString()).apply()

        val trusted = TrustedStations.list.map { it.copy(lastUpdated = System.currentTimeMillis()) }
        val trustedUrls = trusted.map { normalizeUrl(it.primaryUrl) }.toHashSet()
        val trustedNames = trusted.map { normalizeName(it.name) }.toHashSet()

        stationDao.insertStationsIgnore(trusted)

        val existing = stationDao.getStationsListDirect()
        val stale = existing.filter { st ->
            !st.isFavorite &&
                normalizeUrl(st.primaryUrl) !in trustedUrls &&
                normalizeName(st.name) in trustedNames
        }
        if (stale.isNotEmpty()) {
            stationDao.deleteByUuids(stale.map { it.uuid })
            Log.d("RadioRepository", "حذف ${stale.size} نسخة قديمة مكررة من محطات القائمة الموثوقة")
        }
        Log.d("RadioRepository", "القائمة الموثوقة محفوظة. الإجمالي: " + stationDao.getStationsCount())
    }

    private fun normalizeUrl(url: String): String {
        var u = url.trim().trimEnd('/')
        if (u.endsWith(";")) u = u.dropLast(1).trimEnd('/')
        return u.lowercase()
    }

    private suspend fun mergeStations(newStations: List<StationEntity>) {
        val existing = stationDao.getStationsListDirect()
        val byUuid = existing.associateBy { it.uuid }
        val existingNames = existing.map { normalizeName(it.name) }.toHashSet()
        val existingUrls = existing.map { it.primaryUrl.trim().trimEnd('/') }.toHashSet()

        val toInsert = newStations.mapNotNull { ns ->
            val ex = byUuid[ns.uuid]
            when {
                ex != null && (ns.popularity > ex.popularity || ex.lastUrlWorks == false) ->
                    ns.copy(isFavorite = ex.isFavorite)
                ex != null -> null
                normalizeName(ns.name) in existingNames ||
                    ns.primaryUrl.trim().trimEnd('/') in existingUrls -> null
                ns.popularity < MIN_SERVER_POPULARITY && !isKnownStationName(ns.name) -> null
                else -> {
                    existingNames.add(normalizeName(ns.name))
                    existingUrls.add(ns.primaryUrl.trim().trimEnd('/'))
                    ns
                }
            }
        }
        if (toInsert.isNotEmpty()) {
            stationDao.insertStationsIgnore(toInsert)
        }
    }

    private val trustedNameKeys: HashSet<String> by lazy {
        TrustedStations.list.map { normalizeName(it.name) }.toHashSet()
    }

    private fun isKnownStationName(name: String): Boolean {
        val key = normalizeName(name)
        if (key in trustedNameKeys) return true
        val lower = name.lowercase()
        return KNOWN_KEYWORDS.any { it in lower }
    }

    private val KNOWN_KEYWORDS = listOf(
        "jazeera", "الجزيرة", "sky news", "سكاي", "arabiya", "العربية", "bbc", "mbc",
        "monte carlo", "مونت كارلو", "quran", "قرآن", "nagham", "نغم", "nogoum", "نجوم",
        "9090", "mix fm", "hits", "sha3bi", "rotana", "روتانا", "fairuz", "فيروز",
        "um kulthum", "om kalthoum", "أم كلثوم", "amr diab", "迪اب", "diab", "nrj",
        "medina", "aswat", "أصوات", "orient", "ajyal", "sumair", "sumer", "hala",
        "mona", "lbc", "sawt el ghad", "نساء", "إذاعة", "راديو", "radio"
    )

    private fun normalizeName(name: String): String {
        return name.lowercase()
            .replace(Regex("[\\u064B-\\u0652\\u0670]"), "")
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
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
            seedTrustedStations()
            val count = stationDao.getStationsCount()
            Log.d("RadioRepository", "عدد المحطات الحالي: " + count)
            if (count < TrustedStations.list.size + 50) {
                Log.d("RadioRepository", "عدد المحطات أقل من المتوقع - جاري جلب وتكميل من السيرفر...")
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
