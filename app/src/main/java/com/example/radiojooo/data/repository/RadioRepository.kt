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

        coroutineScope {
            val jobs = listOf(
                async { fetchFromRadioBrowserAllArab() },
                async { fetchFromIPRD() },
                async { fetchFromIslamicAPI() },
                async { fetchFromM3U() },
                async { fetchFromFMStream() },
                async { fetchFromRadioNet() }
            )
            jobs.forEach { fetchedList.addAll(it.await()) }
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

    // ==================== 1. Radio Browser (جميع الدول العربية) ====================

    private fun fetchFromRadioBrowserAllArab(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val arabCountries = listOf(
            "EG", "SA", "AE", "QA", "KW", "BH", "OM", "JO", "LB",
            "SY", "IQ", "PS", "YE", "SD", "LY", "TN", "DZ", "MA",
            "MR", "SO", "DJ", "KM"
        )
        val servers = listOf(
            "https://de1.api.radio-browser.info",
            "https://at1.api.radio-browser.info",
            "https://nl1.api.radio-browser.info",
            "https://fr1.api.radio-browser.info"
        )
        for (country in arabCountries) {
            for (server in servers) {
                try {
                    val url = URL("$server/json/stations/search?countrycode=$country&limit=200&hidebroken=true&bitrateMax=64")
                    val connection = url.openConnection() as HttpURLConnection
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000
                    connection.setRequestProperty("User-Agent", "RadioJooApp/5.0")
                    if (connection.responseCode == 200) {
                        val jsonArray = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val name = obj.optString("name", "محطة راديو").trim()
                            val rawUrl = obj.optString("url_resolved", obj.optString("url", "")).trim()
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
                                        countryCode = country,
                                        bitrate = bitrate,
                                        isFavorite = false,
                                        popularityScore = obj.optInt("clickcount", 100)
                                    )
                                )
                            }
                        }
                        break
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
        for (server in servers) {
            try {
                val url = URL("$server/json/stations/search?language=arabic&limit=500&hidebroken=true&bitrateMax=64")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("User-Agent", "RadioJooApp/5.0")
                if (connection.responseCode == 200) {
                    val jsonArray = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val name = obj.optString("name", "محطة راديو").trim()
                        val rawUrl = obj.optString("url_resolved", obj.optString("url", "")).trim()
                        val country = obj.optString("countrycode", "AR")
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
                                    countryCode = if (country.isBlank()) "AR" else country,
                                    bitrate = bitrate,
                                    isFavorite = false,
                                    popularityScore = obj.optInt("clickcount", 100)
                                )
                            )
                        }
                    }
                    break
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        return list
    }

    // ==================== 2. IPRD ====================

    private fun fetchFromIPRD(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val arabCountries = listOf(
            "EG", "SA", "AE", "QA", "KW", "BH", "OM", "JO", "LB",
            "SY", "IQ", "PS", "YE", "SD", "LY", "TN", "DZ", "MA",
            "MR", "SO", "DJ", "KM"
        )
        val iprdUrls = listOf(
            "https://iprd-org.github.io/iprd/site_data/metadata/catalog.json",
            "https://raw.githubusercontent.com/iprd-org/iprd/main/site_data/metadata/catalog.json"
        )
        for (iprdUrl in iprdUrls) {
            try {
                val connection = URL(iprdUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("User-Agent", "RadioJooApp/5.0")
                if (connection.responseCode == 200) {
                    val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                    val root = JSONArray(jsonText)
                    for (i in 0 until root.length()) {
                        val station = root.getJSONObject(i)
                        val bitrate = station.optInt("bitrate", 128)
                        val country = station.optString("country", "")
                        val language = station.optString("language", "")
                        val isArab = arabCountries.contains(country.uppercase()) ||
                                language.contains("arabic", true) || language.contains("ar", true)
                        if (isArab && bitrate in 1..64) {
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
                                        countryCode = if (country.isBlank()) "AR" else country,
                                        bitrate = bitrate,
                                        isFavorite = false,
                                        popularityScore = station.optInt("votes", 100)
                                    )
                                )
                            }
                        }
                    }
                    break
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        return list
    }

    // ==================== 3. Islamic Radio API ====================

    private fun fetchFromIslamicAPI(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val islamicUrls = listOf(
            "https://raw.githubusercontent.com/uthumany/islamic-radio-api/main/data/stations.json",
            "https://raw.githubusercontent.com/uthumany/islamic-radio-api/master/data/stations.json"
        )
        for (apiUrl in islamicUrls) {
            try {
                val connection = URL(apiUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("User-Agent", "RadioJooApp/5.0")
                if (connection.responseCode == 200) {
                    val jsonArray = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        val name = obj.optString("name", "إذاعة إسلامية").trim()
                        val streamUrl = obj.optString("url", obj.optString("stream_url", "")).trim()
                        val bitrate = obj.optInt("bitrate", 64)
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
                    break
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        return list
    }

    // ==================== 4. قوائم M3U ====================

    private fun fetchFromM3U(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val m3uUrls = listOf(
            "https://raw.githubusercontent.com/mdercq/m3u-playlists/main/arabic.m3u",
            "https://raw.githubusercontent.com/mdercq/m3u-playlists/master/arabic.m3u",
            "https://raw.githubusercontent.com/junguler/m3u-radio-music-playlists/main/arabic.m3u"
        )
        for (m3uUrl in m3uUrls) {
            try {
                val connection = URL(m3uUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("User-Agent", "RadioJooApp/5.0")
                if (connection.responseCode == 200) {
                    val lines = connection.inputStream.bufferedReader().use { it.readLines() }
                    var currentName = ""
                    for (line in lines) {
                        if (line.startsWith("#EXTINF:")) {
                            currentName = line.substringAfterLast(",").trim()
                        } else if (line.startsWith("http") && currentName.isNotEmpty()) {
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
                                        bitrate = 64,
                                        isFavorite = false,
                                        popularityScore = 50
                                    )
                                )
                            }
                            currentName = ""
                        }
                    }
                    break
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        return list
    }

    // ==================== 5. fmstream.org ====================

    private fun fetchFromFMStream(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        try {
            // fmstream.org API (غير رسمي لكنه يوفر قوائم محطات)
            val url = URL("https://fmstream.org/api/stations?limit=500")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "RadioJooApp/5.0")
            if (connection.responseCode == 200) {
                val jsonArray = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val name = obj.optString("name", "محطة").trim()
                    val country = obj.optString("country", "")
                    val bitrate = obj.optInt("bitrate", 128)
                    val streamUrl = obj.optString("stream", "").trim()
                    val isArab = country.equals("EG", true) || country.equals("SA", true) ||
                            country.equals("AE", true) || country.equals("QA", true) ||
                            country.equals("KW", true) || country.equals("BH", true) ||
                            country.equals("OM", true) || country.equals("JO", true) ||
                            country.equals("LB", true) || country.equals("SY", true) ||
                            country.equals("IQ", true) || country.equals("PS", true) ||
                            country.equals("YE", true) || country.equals("SD", true) ||
                            country.equals("LY", true) || country.equals("TN", true) ||
                            country.equals("DZ", true) || country.equals("MA", true)
                    if (isArab && streamUrl.isNotBlank() && bitrate in 1..64) {
                        val uuid = "fmstream_${name.hashCode()}_${streamUrl.hashCode()}"
                        val category = StationNormalizer.classify(uuid, name, "")
                        list.add(
                            StationEntity(
                                uuid = uuid,
                                name = name,
                                primaryUrl = wrapProxy(streamUrl),
                                fallbackUrl = "",
                                tags = "arabic,fmstream",
                                country = country,
                                category = category,
                                countryCode = if (country.isBlank()) "AR" else country,
                                bitrate = bitrate,
                                isFavorite = false,
                                popularityScore = 60
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    // ==================== 6. radio.net (API غير رسمي) ====================

    private fun fetchFromRadioNet(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        try {
            // radio.net API غير الرسمي (قد يتغير)
            val url = URL("http://api.radio.net/info/v2/search/stations?country=EG&size=200")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "RadioJooApp/5.0")
            if (connection.responseCode == 200) {
                val jsonText = connection.inputStream.bufferedReader().use { it.readText() }
                // محاولة قراءة JSON (قد يكون الشكل مختلفاً)
                val root = JSONArray(jsonText)
                for (i in 0 until root.length()) {
                    val obj = root.getJSONObject(i)
                    val name = obj.optString("name", "محطة").trim()
                    val streamUrl = obj.optString("streamUrl", obj.optString("url", "")).trim()
                    val bitrate = obj.optInt("bitrate", 128)
                    val country = obj.optString("country", "EG")
                    if (streamUrl.isNotBlank() && bitrate in 1..64) {
                        val uuid = "radionet_${name.hashCode()}_${streamUrl.hashCode()}"
                        val category = StationNormalizer.classify(uuid, name, "")
                        list.add(
                            StationEntity(
                                uuid = uuid,
                                name = name,
                                primaryUrl = wrapProxy(streamUrl),
                                fallbackUrl = "",
                                tags = "arabic,radionet",
                                country = country,
                                category = category,
                                countryCode = country,
                                bitrate = bitrate,
                                isFavorite = false,
                                popularityScore = 80
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // radio.net قد لا يعمل، نتجاهل الخطأ
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
