package com.example.radiojooo.data.repository

import com.example.radiojooo.data.local.StationDao
import com.example.radiojooo.data.model.StationEntity
import com.example.radiojooo.data.util.StationNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class RadioRepository(private val stationDao: StationDao) {

    private val PROXY_BASE_URL = "https://radio-joo-proxy.midoc11112.workers.dev/?stream_url="

    val allStations: Flow<List<StationEntity>> = stationDao.getAllStations()

    private fun wrapProxy(rawUrl: String): String {
        if (rawUrl.isBlank()) return ""
        if (rawUrl.contains("radio-joo-proxy")) return rawUrl
        return try {
            "$PROXY_BASE_URL${URLEncoder.encode(rawUrl, "UTF-8")}"
        } catch (e: Exception) {
            "$PROXY_BASE_URL$rawUrl"
        }
    }

    suspend fun ensureInitialDataLoaded() {
        withContext(Dispatchers.IO) {
            if (stationDao.getStationsCount() == 0) {
                // ✅ جلب ديناميكي من المصدر
                refreshStations()
                // ✅ إذا فشل الإنترنت تماماً، نضيف 3 محطات طوارئ فقط
                if (stationDao.getStationsCount() == 0) {
                    insertEmergencyStations()
                }
            }
        }
    }

    suspend fun refreshStations(): Int = withContext(Dispatchers.IO) {
        var insertedCount = 0
        val fetchedList = mutableListOf<StationEntity>()

        val limit = 200
        val offsets = listOf(0, 200, 400, 600, 800)

        for (offset in offsets) {
            val pageUrls = listOf(
                // ✅ إضافة order=clickcount&reverse=true لجلب المحطات الأكثر شعبية والتي تعمل أولاً
                "https://de1.api.radio-browser.info/json/stations/search?countrycode=EG&limit=$limit&offset=$offset&hidebroken=true&order=clickcount&reverse=true",
                "https://de1.api.radio-browser.info/json/stations/search?language=arabic&limit=$limit&offset=$offset&hidebroken=true&order=clickcount&reverse=true"
            )

            for (apiUrl in pageUrls) {
                try {
                    val url = URL(apiUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    connection.setRequestProperty("User-Agent", "RadioJooApp/3.0")

                    if (connection.responseCode == 200) {
                        val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                        val jsonArray = JSONArray(jsonText)

                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val name = obj.optString("name", "محطة راديو").trim()
                            val rawStreamUrl = obj.optString("url_resolved", obj.optString("url", "")).trim()
                            val country = obj.optString("countrycode", "EG")
                            val tags = obj.optString("tags", "عام")
                            val bitrate = obj.optInt("bitrate", 32)
                            val uuid = obj.optString("stationuuid", java.util.UUID.randomUUID().toString())

                            if (rawStreamUrl.isNotBlank()) {
                                val category = StationNormalizer.classify(uuid = uuid, name = name, tags = tags)
                                val proxiedUrl = wrapProxy(rawStreamUrl)

                                fetchedList.add(
                                    StationEntity(
                                        uuid = uuid,
                                        name = name,
                                        primaryUrl = proxiedUrl,
                                        fallbackUrl = "",
                                        tags = tags,
                                        country = country,
                                        category = category,
                                        countryCode = if (country.isBlank()) "EG" else country,
                                        bitrate = if (bitrate <= 0) 32 else bitrate,
                                        isFavorite = false,
                                        popularityScore = obj.optInt("clickcount", 100)
                                    )
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        if (fetchedList.isNotEmpty()) {
            val distinctList = fetchedList.distinctBy { it.uuid }
            val currentFavs = stationDao.getStationsListDirect().filter { it.isFavorite }.map { it.uuid }.toSet()
            val updated = distinctList.map { it.copy(isFavorite = currentFavs.contains(it.uuid)) }
            stationDao.insertStations(updated)
            insertedCount = updated.size
        }

        insertedCount
    }

    // ✅ 3 محطات طوارئ فقط (تعمل دائماً حتى لو فشل الاتصال بالمصدر)
    private suspend fun insertEmergencyStations() {
        val emergencyList = listOf(
            StationEntity(uuid = "emergency_quran", name = "إذاعة القرآن الكريم", primaryUrl = wrapProxy("https://stream.zeno.fm/f3wvbb1ch3quv"), fallbackUrl = "", tags = "quran", country = "EG", category = "QURAN", countryCode = "EG", bitrate = 32, isFavorite = true, popularityScore = 1000),
            StationEntity(uuid = "emergency_nogoum", name = "نجوم إف إم", primaryUrl = wrapProxy("https://audio.nrpstream.com/listen/nogoumfm/radio.mp3"), fallbackUrl = "", tags = "music", country = "EG", category = "MUSIC", countryCode = "EG", bitrate = 128, isFavorite = false, popularityScore = 990),
            StationEntity(uuid = "emergency_radio_masr", name = "راديو مصر", primaryUrl = wrapProxy("https://audio.nrpstream.com/listen/radio_masr/radio.mp3"), fallbackUrl = "", tags = "news", country = "EG", category = "NEWS", countryCode = "EG", bitrate = 64, isFavorite = false, popularityScore = 980)
        )
        stationDao.insertStations(emergencyList)
    }

    suspend fun toggleFavorite(uuid: String, currentStatus: Boolean) {
        stationDao.updateFavoriteStatus(uuid, !currentStatus)
    }
}
