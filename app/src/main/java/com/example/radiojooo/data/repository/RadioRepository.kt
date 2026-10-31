package com.example.radiojooo.data.repository

import com.example.radiojooo.data.local.StationDao
import com.example.radiojooo.data.model.StationEntity
import com.example.radiojooo.data.util.StationNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
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

    // ✅ الحل الثوري: التركيز على مصدرين فقط، مهلة 20 ثانية لكل مصدر، بدون فلتر bitrate صارم
    suspend fun refreshStations(): Int = withContext(Dispatchers.IO) {
        val fetchedList = mutableListOf<StationEntity>()

        supervisorScope {
            val jobs = listOf(
                async { withTimeoutOrNull(20_000L) { fetchFromEgyptM3U() } },
                async { withTimeoutOrNull(20_000L) { fetchFromArabicM3U() } }
            )
            val results = jobs.awaitAll()
            results.filterNotNull().forEach { list ->
                fetchedList.addAll(list)
            }
        }

        if (fetchedList.isNotEmpty()) {
            val distinctList = fetchedList.distinctBy { it.uuid }
            val currentFavs = stationDao.getStationsListDirect().filter { it.isFavorite }.map { it.uuid }.toSet()
            val updated = distinctList.map { it.copy(isFavorite = currentFavs.contains(it.uuid)) }
            stationDao.insertStations(updated)
            updated.size
        } else {
            insertEmergencyStations()
            stationDao.getStationsCount()
        }
    }

    // ==================== 1. مصر (كنز المحطات المصرية) ====================
    private fun fetchFromEgyptM3U(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val urls = listOf(
            "https://git.binarymagic.net/AIR/m3u-radio-music-playlists/raw/branch/master/+merged+/e/egypt.m3u",
            "https://git.binarymagic.net/AIR/m3u-radio-music-playlists/raw/branch/master/zeno.fm/Arabic_(Egypt).m3u"
        )
        for (urlStr in urls) {
            try {
                val connection = URL(urlStr).openConnection() as HttpURLConnection
                connection.connectTimeout = 15000
                connection.readTimeout = 15000
                connection.setRequestProperty("User-Agent", "RadioJooApp/12.0")
                if (connection.responseCode == 200) {
                    val lines = connection.inputStream.bufferedReader().use { it.readLines() }
                    var currentName = ""
                    for (line in lines) {
                        if (line.startsWith("#EXTINF:")) {
                            currentName = line.substringAfterLast(",").trim()
                        } else if (line.startsWith("http") && currentName.isNotEmpty()) {
                            val lowerUrl = line.lowercase()
                            val lowerName = currentName.lowercase()
                            // نأخذ المحطات المصرية المعروفة أو التي تحتوي على 64/32/low في الاسم أو الرابط
                            val isEgyptian = lowerName.contains("masr") || lowerName.contains("nogoum") ||
                                    lowerName.contains("nile") || lowerName.contains("9090") ||
                                    lowerName.contains("mega") || lowerName.contains("sha3by") ||
                                    lowerName.contains("nagham") || lowerName.contains("radio masr") ||
                                    lowerName.contains("goal") || lowerName.contains("diab") ||
                                    lowerName.contains("banha") || lowerName.contains("quran")
                            val isLowBitrate = lowerUrl.contains("64") || lowerUrl.contains("32") ||
                                    lowerUrl.contains("low") || lowerName.contains("64") || lowerName.contains("32")
                            if (isEgyptian || isLowBitrate) {
                                val uuid = "egypt_${currentName.hashCode()}_${line.hashCode()}"
                                val category = StationNormalizer.classify(uuid, currentName, "arabic")
                                list.add(
                                    StationEntity(
                                        uuid = uuid,
                                        name = currentName,
                                        primaryUrl = wrapProxy(line),
                                        fallbackUrl = "",
                                        tags = "egypt,arabic",
                                        country = "EG",
                                        category = category,
                                        countryCode = "EG",
                                        bitrate = 64, // قيمة افتراضية للعرض
                                        isFavorite = false,
                                        popularityScore = 80
                                    )
                                )
                            }
                            currentName = ""
                        }
                    }
                    break // نجح الجلب من هذا الرابط
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        return list
    }

    // ==================== 2. عربي (مع bitrate صريح) ====================
    private fun fetchFromArabicM3U(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val url = "https://git.binarymagic.net/AIR/m3u-radio-music-playlists/raw/branch/master/arabic.m3u"
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "RadioJooApp/12.0")
            if (connection.responseCode == 200) {
                val lines = connection.inputStream.bufferedReader().use { it.readLines() }
                var currentName = ""
                for (line in lines) {
                    if (line.startsWith("#EXTINF:")) {
                        currentName = line.substringAfterLast(",").trim()
                    } else if (line.startsWith("http") && currentName.isNotEmpty()) {
                        // استخراج bitrate من الاسم (مثل "48 kbit/s")
                        val bitrateMatch = Regex("(\\d+)\\s*kbit/s").find(currentName)
                        val bitrate = bitrateMatch?.groupValues?.get(1)?.toIntOrNull() ?: 128
                        // نأخذ المحطات التي يقل bitrate فيها عن 128 (أو غير معروف)
                        if (bitrate <= 128) {
                            val uuid = "arabic_${currentName.hashCode()}_${line.hashCode()}"
                            val category = StationNormalizer.classify(uuid, currentName, "arabic")
                            list.add(
                                StationEntity(
                                    uuid = uuid,
                                    name = currentName,
                                    primaryUrl = wrapProxy(line),
                                    fallbackUrl = "",
                                    tags = "arabic",
                                    country = "AR",
                                    category = category,
                                    countryCode = "AR",
                                    bitrate = bitrate,
                                    isFavorite = false,
                                    popularityScore = 70
                                )
                            )
                        }
                        currentName = ""
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

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
