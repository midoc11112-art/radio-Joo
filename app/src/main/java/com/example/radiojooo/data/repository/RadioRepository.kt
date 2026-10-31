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
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class RadioRepository(private val stationDao: StationDao, cacheDir: File) {

    private val PROXY_BASE_URL = "https://radio-joo-proxy.midoc11112.workers.dev/?stream_url="

    // ✅ إعداد OkHttp مع كاش 10 ميجابايت لدعم Delta Sync و ETag
    private val okHttpClient = OkHttpClient.Builder()
        .cache(Cache(File(cacheDir, "radio_http_cache"), 10L * 1024 * 1024))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

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
            // 1. عرض المحطات المخزنة فوراً (Offline-First)
            if (stationDao.getStationsCount() == 0) {
                stationDao.insertStations(getSeedStations())
            }
            
            // 2. بدء التحديث الصامت في الخلفية
            refreshStations()
        }
    }

    suspend fun refreshStations(): Int = withContext(Dispatchers.IO) {
        val fetchedList = mutableListOf<StationEntity>()

        // ✅ الجلب المتوازي من المصادر التي ذكرتها في فكرتك
        supervisorScope {
            val jobs = listOf(
                async { withTimeoutOrNull(15_000L) { fetchFromRadioBrowserEG() } },
                async { withTimeoutOrNull(15_000L) { fetchFromRadioBrowserArabic() } },
                async { withTimeoutOrNull(15_000L) { fetchFromRadioBrowserNews() } },
                async { withTimeoutOrNull(15_000L) { fetchFromIptvOrgArabic() } },
                async { withTimeoutOrNull(15_000L) { fetchFromEgyptM3U() } }
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
            stationDao.getStationsCount()
        } else {
            stationDao.getStationsCount()
        }
    }

    // ==================== 1. Radio-Browser (محطات مصر) ====================
    private fun fetchFromRadioBrowserEG(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val url = "https://de1.api.radio-browser.info/json/stations/bycountrycodeexact/EG"
        try {
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful || response.code == 304) {
                val jsonArray = JSONArray(response.body?.string() ?: "[]")
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val name = obj.optString("name", "محطة").trim()
                    val rawUrl = obj.optString("url_resolved", obj.optString("url", "")).trim()
                    val bitrate = obj.optInt("bitrate", 64)
                    val uuid = obj.optString("stationuuid", java.util.UUID.randomUUID().toString())
                    if (rawUrl.isNotBlank() && bitrate <= 128) {
                        val category = StationNormalizer.classify(uuid, name, "arabic")
                        list.add(
                            StationEntity(
                                uuid = uuid,
                                name = name,
                                primaryUrl = wrapProxy(rawUrl),
                                fallbackUrl = "",
                                tags = "egypt,arabic",
                                country = "EG",
                                category = category,
                                countryCode = "EG",
                                bitrate = bitrate,
                                isFavorite = false,
                                popularityScore = 80
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    // ==================== 2. Radio-Browser (اللغة العربية) ====================
    private fun fetchFromRadioBrowserArabic(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val url = "https://de1.api.radio-browser.info/json/stations/bylanguage/arabic"
        try {
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful || response.code == 304) {
                val jsonArray = JSONArray(response.body?.string() ?: "[]")
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val name = obj.optString("name", "محطة").trim()
                    val rawUrl = obj.optString("url_resolved", obj.optString("url", "")).trim()
                    val bitrate = obj.optInt("bitrate", 64)
                    val country = obj.optString("countrycode", "AR")
                    val uuid = obj.optString("stationuuid", java.util.UUID.randomUUID().toString())
                    if (rawUrl.isNotBlank() && bitrate <= 128) {
                        val category = StationNormalizer.classify(uuid, name, "arabic")
                        list.add(
                            StationEntity(
                                uuid = uuid,
                                name = name,
                                primaryUrl = wrapProxy(rawUrl),
                                fallbackUrl = "",
                                tags = "arabic",
                                country = country,
                                category = category,
                                countryCode = if (country.isBlank()) "AR" else country,
                                bitrate = bitrate,
                                isFavorite = false,
                                popularityScore = 70
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    // ==================== 3. Radio-Browser (الأخبار والتوك شو) ====================
    private fun fetchFromRadioBrowserNews(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val url = "https://de1.api.radio-browser.info/json/stations/bytag/news"
        try {
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful || response.code == 304) {
                val jsonArray = JSONArray(response.body?.string() ?: "[]")
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val name = obj.optString("name", "محطة").trim()
                    val rawUrl = obj.optString("url_resolved", obj.optString("url", "")).trim()
                    val bitrate = obj.optInt("bitrate", 64)
                    val country = obj.optString("countrycode", "AR")
                    val uuid = obj.optString("stationuuid", java.util.UUID.randomUUID().toString())
                    if (rawUrl.isNotBlank() && bitrate <= 128) {
                        val category = "NEWS"
                        list.add(
                            StationEntity(
                                uuid = uuid,
                                name = name,
                                primaryUrl = wrapProxy(rawUrl),
                                fallbackUrl = "",
                                tags = "news,talkshow",
                                country = country,
                                category = category,
                                countryCode = if (country.isBlank()) "AR" else country,
                                bitrate = bitrate,
                                isFavorite = false,
                                popularityScore = 75
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    // ==================== 4. IPTV-org (قائمة اللغة العربية) ====================
    private fun fetchFromIptvOrgArabic(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val url = "https://iptv-org.github.io/iptv/languages/ara.m3u"
        try {
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful || response.code == 304) {
                val lines = response.body?.string()?.lines() ?: emptyList()
                var currentName = ""
                for (line in lines) {
                    if (line.startsWith("#EXTINF:")) {
                        currentName = line.substringAfterLast(",").trim()
                    } else if (line.startsWith("http") && currentName.isNotEmpty()) {
                        val lowerUrl = line.lowercase()
                        val isVideo = lowerUrl.contains(".m3u8") || lowerUrl.contains(".mp4") || lowerUrl.contains(".ts") || lowerUrl.contains("video")
                        if (!isVideo) {
                            val uuid = "iptvarabic_${currentName.hashCode()}_${line.hashCode()}"
                            val category = StationNormalizer.classify(uuid, currentName, "arabic")
                            list.add(
                                StationEntity(
                                    uuid = uuid,
                                    name = currentName,
                                    primaryUrl = wrapProxy(line),
                                    fallbackUrl = "",
                                    tags = "arabic,iptv",
                                    country = "AR",
                                    category = category,
                                    countryCode = "AR",
                                    bitrate = 64,
                                    isFavorite = false,
                                    popularityScore = 60
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

    // ==================== 5. Egypt M3U (كنز المحطات المصرية) ====================
    private fun fetchFromEgyptM3U(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val url = "https://git.binarymagic.net/AIR/m3u-radio-music-playlists/raw/branch/master/+merged+/e/egypt.m3u"
        try {
            val request = Request.Builder().url(url).build()
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful || response.code == 304) {
                val lines = response.body?.string()?.lines() ?: emptyList()
                var currentName = ""
                for (line in lines) {
                    if (line.startsWith("#EXTINF:")) {
                        currentName = line.substringAfterLast(",").trim()
                    } else if (line.startsWith("http") && currentName.isNotEmpty()) {
                        val lowerName = currentName.lowercase()
                        val isEgyptian = lowerName.contains("masr") || lowerName.contains("nogoum") ||
                                lowerName.contains("nile") || lowerName.contains("9090") ||
                                lowerName.contains("mega") || lowerName.contains("sha3by") ||
                                lowerName.contains("nagham") || lowerName.contains("radio") ||
                                lowerName.contains("quran")
                        if (isEgyptian) {
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
                                    bitrate = 64,
                                    isFavorite = false,
                                    popularityScore = 85
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

    // ==================== 6. محطات مضمونة (Seed) ====================
    private fun getSeedStations(): List<StationEntity> {
        return listOf(
            StationEntity("eg_quran_cairo", "إذاعة القرآن الكريم - القاهرة", wrapProxy("https://stream.zeno.fm/f3wvbb1ch3quv"), "", "quran", "EG", "QURAN", 32, true, 10000),
            StationEntity("eg_radio_masr", "راديو مصر 88.7", wrapProxy("https://audio.nrpstream.com/listen/radio_masr/radio.mp3"), "", "news", "EG", "NEWS", 64, true, 9900),
            StationEntity("eg_nogoum_fm", "نجوم إف إم 100.6", wrapProxy("https://audio.nrpstream.com/listen/nogoumfm/radio.mp3"), "", "music", "EG", "MUSIC", 128, true, 9950),
            StationEntity("eg_nile_fm", "نايل إف إم 104.2", wrapProxy("https://audio.nrpstream.com/listen/nile_fm/radio.mp3"), "", "music", "EG", "MUSIC", 128, true, 9940),
            StationEntity("eg_9090_fm", "راديو 9090", wrapProxy("https://9090streaming.mobtada.com/9090FMEGYPT"), "", "news", "EG", "NEWS", 64, true, 9850),
            StationEntity("eg_mega_fm", "ميجا إف إم 92.7", wrapProxy("http://nebula.shoutca.st:8211/mp3"), "", "music", "EG", "MUSIC", 128, true, 9850),
            StationEntity("eg_sha3by_fm", "شعبي إف إم 95.0", wrapProxy("https://radio95.radioca.st/"), "", "music", "EG", "MUSIC", 128, true, 9750),
            StationEntity("eg_nagham_fm", "نغم إف إم 105.3", wrapProxy("https://ahmsamir.radioca.st/stream"), "", "music", "EG", "MUSIC", 128, true, 9800),
            StationEntity("ar_jazeera_voice", "الجزيرة - صوت", wrapProxy("https://live-hls-audio-web-aja.getaj.net/VOICE-AJA/01.m3u8"), "", "news", "QA", "NEWS", 64, true, 9900),
            StationEntity("ar_alarabiya", "العربية - صوت", wrapProxy("https://live.alarabiya.net/alarabiya/live.m3u8"), "", "news", "SA", "NEWS", 64, true, 9850),
            StationEntity("ar_bbc_arabic", "بي بي سي عربي", wrapProxy("https://stream.live.vc.bbcmedia.co.uk/bbc_arabic_radio"), "", "news", "GB", "NEWS", 64, true, 9700)
        )
    }

    suspend fun toggleFavorite(uuid: String, currentStatus: Boolean) {
        stationDao.updateFavoriteStatus(uuid, !currentStatus)
    }
}
