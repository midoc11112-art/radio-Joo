package com.example.radiojooo.data.repository

import com.example.radiojooo.data.local.StationDao
import com.example.radiojooo.data.model.StationEntity
import com.example.radiojooo.data.util.StationNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
                refreshStations()
                if (stationDao.getStationsCount() == 0) {
                    insertEmergencyStations()
                }
            }
        }
    }

    suspend fun refreshStations(): Int = withContext(Dispatchers.IO) {
        val fetchedList = mutableListOf<StationEntity>()

        // 1. جلب من Radio Browser (فلترة bitrate ≤ 64)
        coroutineScope {
            val jobs = (0 until 5).map { page ->
                async { fetchFromRadioBrowser(page) }
            }
            jobs.forEach { fetchedList.addAll(it.await()) }
        }

        // 2. جلب من IPRD (فلترة bitrate ≤ 64)
        coroutineScope {
            val iprdJob = async { fetchFromIPRD() }
            fetchedList.addAll(iprdJob.await())
        }

        // 3. جلب من Islamic Radio API (فلترة bitrate ≤ 64)
        coroutineScope {
            val islamicJob = async { fetchFromIslamicAPI() }
            fetchedList.addAll(islamicJob.await())
        }

        // 4. جلب من قوائم M3U (mdercq) واستخراج الروابط الخفيفة
        coroutineScope {
            val m3uJob = async { fetchFromM3U() }
            fetchedList.addAll(m3uJob.await())
        }

        if (fetchedList.isNotEmpty()) {
            val distinctList = fetchedList.distinctBy { it.uuid }
            val currentFavs = stationDao.getStationsListDirect().filter { it.isFavorite }.map { it.uuid }.toSet()
            val updated = distinctList.map { it.copy(isFavorite = currentFavs.contains(it.uuid)) }
            stationDao.insertStations(updated)
            updated.size
        } else {
            insertEmergencyStations()
            0
        }
    }

    // ==================== دوال الجلب من المصادر ====================

    private fun fetchFromRadioBrowser(page: Int): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val limit = 200
        val offset = page * 200
        val urls = listOf(
            "https://de1.api.radio-browser.info/json/stations/search?countrycode=EG&limit=$limit&offset=$offset&hidebroken=true&bitrateMax=64",
            "https://de1.api.radio-browser.info/json/stations/search?language=arabic&limit=$limit&offset=$offset&hidebroken=true&bitrateMax=64"
        )
        for (apiUrl in urls) {
            try {
                val connection = URL(apiUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("User-Agent", "RadioJooApp/4.0")
                if (connection.responseCode == 200) {
                    val jsonArray = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val name = obj.optString("name", "محطة راديو").trim()
                        val rawUrl = obj.optString("url_resolved", obj.optString("url", "")).trim()
                        val country = obj.optString("countrycode", "EG")
                        val tags = obj.optString("tags", "عام")
                        val bitrate = obj.optInt("bitrate", 32)
                        val uuid = obj.optString("stationuuid", java.util.UUID.randomUUID().toString())
                        if (rawUrl.isNotBlank() && bitrate in 1..64) {
                            val category = StationNormalizer.classify(uuid, name, tags)
                            list.add(
                                StationEntity(
                                    uuid = uuid,
                                    name = name,
                                    primaryUrl = wrapProxy(rawUrl),
                                    fallbackUrl = "",
                                    tags = tags,
                                    country = country,
                                    category = category,
                                    countryCode = if (country.isBlank()) "EG" else country,
                                    bitrate = bitrate,
                                    isFavorite = false,
                                    popularityScore = obj.optInt("clickcount", 100)
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        return list
    }

    private fun fetchFromIPRD(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        try {
            // كتالوج IPRD الرئيسي
            val url = URL("https://iprd-org.github.io/iprd/site_data/metadata/catalog.json")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "RadioJooApp/4.0")
            if (connection.responseCode == 200) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONArray(jsonText)
                for (i in 0 until root.length()) {
                    val station = root.getJSONObject(i)
                    val bitrate = station.optInt("bitrate", 128)
                    val country = station.optString("country", "")
                    val language = station.optString("language", "")
                    val isArabic = country.equals("EG", true) || country.equals("SA", true) ||
                            country.equals("AE", true) || country.equals("QA", true) ||
                            language.contains("arabic", true) || language.contains("ar", true)
                    if (isArabic && bitrate in 1..64) {
                        val name = station.optString("name", "محطة").trim()
                        val streamUrl = station.optString("stream_url", "").trim()
                        if (streamUrl.isNotBlank()) {
                            val uuid = station.optString("id", java.util.UUID.randomUUID().toString())
                            val tags = station.optString("tags", "عام")
                            val category = StationNormalizer.classify(uuid, name, tags)
                            list.add(
                                StationEntity(
                                    uuid = uuid,
                                    name = name,
                                    primaryUrl = wrapProxy(streamUrl),
                                    fallbackUrl = "",
                                    tags = tags,
                                    country = country,
                                    category = category,
                                    countryCode = if (country.isBlank()) "EG" else country,
                                    bitrate = bitrate,
                                    isFavorite = false,
                                    popularityScore = station.optInt("votes", 100)
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    private fun fetchFromIslamicAPI(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        try {
            // Islamic Radio API (raw GitHub JSON)
            val url = URL("https://raw.githubusercontent.com/uthumany/islamic-radio-api/main/data/stations.json")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "RadioJooApp/4.0")
            if (connection.responseCode == 200) {
                val jsonArray = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val name = obj.optString("name", "إذاعة إسلامية").trim()
                    val streamUrl = obj.optString("url", obj.optString("stream_url", "")).trim()
                    val bitrate = obj.optInt("bitrate", 64) // افتراضي 64
                    if (streamUrl.isNotBlank() && bitrate in 1..64) {
                        val uuid = obj.optString("id", java.util.UUID.randomUUID().toString())
                        val country = obj.optString("country", "EG")
                        list.add(
                            StationEntity(
                                uuid = uuid,
                                name = name,
                                primaryUrl = wrapProxy(streamUrl),
                                fallbackUrl = "",
                                tags = "quran,islamic",
                                country = country,
                                category = "QURAN",
                                countryCode = if (country.isBlank()) "EG" else country,
                                bitrate = bitrate,
                                isFavorite = false,
                                popularityScore = 500
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    private fun fetchFromM3U(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val m3uUrls = listOf(
            "https://raw.githubusercontent.com/mdercq/m3u-playlists/main/arabic.m3u",
            "https://raw.githubusercontent.com/mdercq/m3u-playlists/main/quran.m3u"
        )
        for (m3uUrl in m3uUrls) {
            try {
                val connection = URL(m3uUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("User-Agent", "RadioJooApp/4.0")
                if (connection.responseCode == 200) {
                    val lines = connection.inputStream.bufferedReader().use { it.readLines() }
                    var currentName = ""
                    for (line in lines) {
                        if (line.startsWith("#EXTINF:")) {
                            currentName = line.substringAfterLast(",").trim()
                        } else if (line.startsWith("http") && currentName.isNotEmpty()) {
                            // فلترة بسيطة: نأخذ فقط الروابط التي تحتوي على "64" أو "32" أو "low" في اسمها
                            val lowerUrl = line.lowercase()
                            val lowerName = currentName.lowercase()
                            val isLowBitrate = lowerUrl.contains("64") || lowerUrl.contains("32") ||
                                    lowerUrl.contains("low") || lowerName.contains("64") || lowerName.contains("32")
                            if (isLowBitrate) {
                                val uuid = "m3u_${currentName.hashCode()}_${line.hashCode()}"
                                list.add(
                                    StationEntity(
                                        uuid = uuid,
                                        name = currentName,
                                        primaryUrl = wrapProxy(line),
                                        fallbackUrl = "",
                                        tags = "arabic,m3u",
                                        country = "AR",
                                        category = StationNormalizer.classify(uuid, currentName, "arabic"),
                                        countryCode = "AR",
                                        bitrate = 64, // افتراضي
                                        isFavorite = false,
                                        popularityScore = 50
                                    )
                                )
                            }
                            currentName = ""
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        return list
    }

    // ==================== دوال مساعدة ====================

    private suspend fun insertEmergencyStations() {
        val emergencyList = listOf(
            StationEntity(uuid = "emergency_quran", name = "إذاعة القرآن الكريم", primaryUrl = wrapProxy("https://stream.zeno.fm/f3wvbb1ch3quv"), fallbackUrl = "", tags = "quran", country = "EG", category = "QURAN", countryCode = "EG", bitrate = 32, isFavorite = true, popularityScore = 1000),
            StationEntity(uuid = "emergency_radio_masr", name = "راديو مصر", primaryUrl = wrapProxy("https://audio.nrpstream.com/listen/radio_masr/radio.mp3"), fallbackUrl = "", tags = "news", country = "EG", category = "NEWS", countryCode = "EG", bitrate = 64, isFavorite = false, popularityScore = 990)
        )
        stationDao.insertStations(emergencyList)
    }

    suspend fun toggleFavorite(uuid: String, currentStatus: Boolean) {
        stationDao.updateFavoriteStatus(uuid, !currentStatus)
    }
}
