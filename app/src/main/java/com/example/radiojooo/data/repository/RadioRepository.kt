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
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

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
                async { fetchFromRadioBrowserByTags() },
                async { fetchFromIPRD() },
                async { fetchFromIslamicAPI() },
                async { fetchFromM3U() },
                async { fetchFromTuneIn() },
                async { fetchFromIHeart() },
                async { fetchFromRadioGarden() },
                async { fetchFromMp3Quran() },
                async { fetchFromCountryM3U() },
                async { fetchFromIptvOrg() },
                async { fetchFromM3URadioMusic() },
                async { fetchFromRadioPlayer() },
                async { fetchFromIslamicAPIUpdated() },   // ✅ جديد
                async { fetchFromCoranProM3U() },         // ✅ جديد
                async { fetchFromMp3QuranM3U() },         // ✅ جديد
                async { fetchFromArabicMusicM3U() }       // ✅ جديد
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

    // ... (جميع الدوال السابقة تبقى كما هي: fetchFromRadioBrowserAllArab, fetchFromRadioBrowserByTags, fetchFromIPRD, fetchFromIslamicAPI, fetchFromM3U, fetchFromTuneIn, fetchFromIHeart, fetchFromRadioGarden, fetchFromMp3Quran, fetchFromCountryM3U, fetchFromIptvOrg, fetchFromM3URadioMusic, fetchFromRadioPlayer)
    // سأضيف فقط الدوال الجديدة أدناه، لتوفير المساحة.

    // ==================== 14. Islamic Radio API (الرابط المحدث) ====================
    private fun fetchFromIslamicAPIUpdated(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        try {
            val url = URL("https://raw.githubusercontent.com/uthumany/radio-api/main/client/public/api/stations.json")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "RadioJooApp/8.0")
            if (connection.responseCode == 200) {
                val jsonArray = JSONArray(connection.inputStream.bufferedReader().use { it.readText() })
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val name = obj.optString("name", "إذاعة إسلامية").trim()
                    val streamUrl = obj.optString("url", obj.optString("stream_url", "")).trim()
                    // هذا الـ API قد لا يوفر bitrate، سنفترض 64 كحد أقصى
                    if (streamUrl.isNotBlank()) {
                        val uuid = "islamic2_${name.hashCode()}_${streamUrl.hashCode()}"
                        list.add(
                            StationEntity(
                                uuid = uuid,
                                name = name,
                                primaryUrl = wrapProxy(streamUrl),
                                fallbackUrl = "",
                                tags = "quran,islamic",
                                country = "SA",
                                category = "QURAN",
                                countryCode = "SA",
                                bitrate = 64,
                                isFavorite = false,
                                popularityScore = 55
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    // ==================== 15. Coran Pro M3U ====================
    private fun fetchFromCoranProM3U(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        try {
            val url = URL("https://raw.githubusercontent.com/ismailiabderrahim/perso/main/Coran%20Pro.m3u")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "RadioJooApp/8.0")
            if (connection.responseCode == 200) {
                val lines = connection.inputStream.bufferedReader().use { it.readLines() }
                var currentName = ""
                for (line in lines) {
                    if (line.startsWith("#EXTINF:")) {
                        currentName = line.substringAfterLast(",").trim()
                    } else if (line.startsWith("http") && currentName.isNotEmpty()) {
                        val uuid = "coranpro_${currentName.hashCode()}_${line.hashCode()}"
                        list.add(
                            StationEntity(
                                uuid = uuid,
                                name = currentName,
                                primaryUrl = wrapProxy(line),
                                fallbackUrl = "",
                                tags = "quran,coran",
                                country = "SA",
                                category = "QURAN",
                                countryCode = "SA",
                                bitrate = 64,
                                isFavorite = false,
                                popularityScore = 60
                            )
                        )
                        currentName = ""
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    // ==================== 16. mp3Quran Radio M3U ====================
    private fun fetchFromMp3QuranM3U(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        try {
            val url = URL("https://raw.githubusercontent.com/ismailiabderrahim/perso/main/mp3Quran_Radio.m3u")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "RadioJooApp/8.0")
            if (connection.responseCode == 200) {
                val lines = connection.inputStream.bufferedReader().use { it.readLines() }
                var currentName = ""
                for (line in lines) {
                    if (line.startsWith("#EXTINF:")) {
                        currentName = line.substringAfterLast(",").trim()
                    } else if (line.startsWith("http") && currentName.isNotEmpty()) {
                        val uuid = "mp3quranm3u_${currentName.hashCode()}_${line.hashCode()}"
                        list.add(
                            StationEntity(
                                uuid = uuid,
                                name = currentName,
                                primaryUrl = wrapProxy(line),
                                fallbackUrl = "",
                                tags = "quran,mp3quran",
                                country = "SA",
                                category = "QURAN",
                                countryCode = "SA",
                                bitrate = 64,
                                isFavorite = false,
                                popularityScore = 65
                            )
                        )
                        currentName = ""
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    // ==================== 17. Arabic Music M3U (binarymagic) ====================
    private fun fetchFromArabicMusicM3U(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        try {
            val url = URL("https://git.binarymagic.net/AIR/m3u-radio-music-playlists/raw/branch/master/zeno.fm/Arabic.m3u")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("User-Agent", "RadioJooApp/8.0")
            if (connection.responseCode == 200) {
                val lines = connection.inputStream.bufferedReader().use { it.readLines() }
                var currentName = ""
                for (line in lines) {
                    if (line.startsWith("#EXTINF:")) {
                        currentName = line.substringAfterLast(",").trim()
                    } else if (line.startsWith("http") && currentName.isNotEmpty()) {
                        // فلترة: نأخذ فقط الروابط التي تحتوي على 64 أو 32 أو low
                        val lowerUrl = line.lowercase()
                        val lowerName = currentName.lowercase()
                        val isLow = lowerUrl.contains("64") || lowerUrl.contains("32") ||
                                lowerUrl.contains("low") || lowerName.contains("64") || lowerName.contains("32")
                        if (isLow) {
                            val uuid = "arabicmusic_${currentName.hashCode()}_${line.hashCode()}"
                            list.add(
                                StationEntity(
                                    uuid = uuid,
                                    name = currentName,
                                    primaryUrl = wrapProxy(line),
                                    fallbackUrl = "",
                                    tags = "arabic,music",
                                    country = "AR",
                                    category = "MUSIC",
                                    countryCode = "AR",
                                    bitrate = 64,
                                    isFavorite = false,
                                    popularityScore = 45
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

    // ... (باقي الدوال المساعدة: insertEmergencyStations, toggleFavorite)
}
