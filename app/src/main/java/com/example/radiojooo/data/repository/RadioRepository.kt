package com.example.radiojooo.data.repository

import com.example.radiojooo.data.local.StationDao
import com.example.radiojooo.data.model.StationEntity
import com.example.radiojooo.data.util.StationNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

class RadioRepository(private val stationDao: StationDao, private val cacheDir: File) {

    private val PROXY_BASE_URL = "https://radio-joo-proxy.midoc11112.workers.dev/?stream_url="

    // ✅ الحل السحري للتهنيج: استخدام Lazy حتى يتم تهيئة OkHttp في الخلفية وليس على الـ Main Thread
    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(Cache(File(cacheDir, "radio_http_cache"), 10L * 1024 * 1024))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

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
                stationDao.insertStations(getSeedStations())
            }
            refreshStations()
        }
    }

    suspend fun refreshStations(): Int = withContext(Dispatchers.IO) {
        supervisorScope {
            val jobs = listOf(
                async { withTimeoutOrNull(15_000L) { fetchFromRadioBrowserEG() } },
                async { withTimeoutOrNull(15_000L) { fetchFromRadioBrowserArabic() } },
                async { withTimeoutOrNull(15_000L) { fetchFromRadioBrowserNews() } },
                async { withTimeoutOrNull(15_000L) { fetchFromIptvOrgArabic() } },
                async { withTimeoutOrNull(15_000L) { fetchFromEgyptM3U() } }
            )
            
            jobs.forEach { job ->
                val list = job.await()
                if (list != null && list.isNotEmpty()) {
                    val distinctList = list.distinctBy { it.uuid }
                    val currentFavs = stationDao.getStationsListDirect().filter { it.isFavorite }.map { it.uuid }.toSet()
                    val updated = distinctList.map { it.copy(isFavorite = currentFavs.contains(it.uuid)) }
                    stationDao.insertStations(updated)
                }
            }
        }
        stationDao.getStationsCount()
    }

    private fun fetchFromRadioBrowserEG(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val url = "https://de1.api.radio-browser.info/json/stations/bycountrycodeexact/EG"
        try {
            val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
            if (response.isSuccessful || response.code == 304) {
                val jsonArray = JSONArray(response.body?.string() ?: "[]")
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val name = obj.optString("name", "محطة").trim()
                    val lang = obj.optString("language", "")
                    val country = obj.optString("countrycode", "EG")
                    val rawUrl = obj.optString("url_resolved", obj.optString("url", "")).trim()
                    val bitrate = obj.optInt("bitrate", 64)
                    val uuid = obj.optString("stationuuid", java.util.UUID.randomUUID().toString())
                    
                    if (rawUrl.isNotBlank() && bitrate <= 128 && StationNormalizer.isArabicStation(name, lang, country)) {
                        val category = StationNormalizer.classify(uuid, name, "")
                        list.add(StationEntity(uuid, name, wrapProxy(rawUrl), "", "", country, category, bitrate, 1, false, obj.optInt("clickcount", 100)))
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    private fun fetchFromRadioBrowserArabic(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val url = "https://de1.api.radio-browser.info/json/stations/bylanguage/arabic"
        try {
            val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
            if (response.isSuccessful || response.code == 304) {
                val jsonArray = JSONArray(response.body?.string() ?: "[]")
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val name = obj.optString("name", "محطة").trim()
                    val lang = obj.optString("language", "arabic")
                    val country = obj.optString("countrycode", "AR")
                    val rawUrl = obj.optString("url_resolved", obj.optString("url", "")).trim()
                    val bitrate = obj.optInt("bitrate", 64)
                    val uuid = obj.optString("stationuuid", java.util.UUID.randomUUID().toString())
                    
                    if (rawUrl.isNotBlank() && bitrate <= 128 && StationNormalizer.isArabicStation(name, lang, country)) {
                        val category = StationNormalizer.classify(uuid, name, "")
                        list.add(StationEntity(uuid, name, wrapProxy(rawUrl), "", "", country, category, bitrate, 1, false, obj.optInt("clickcount", 100)))
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    private fun fetchFromRadioBrowserNews(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val url = "https://de1.api.radio-browser.info/json/stations/bytag/news"
        try {
            val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
            if (response.isSuccessful || response.code == 304) {
                val jsonArray = JSONArray(response.body?.string() ?: "[]")
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val name = obj.optString("name", "محطة").trim()
                    val lang = obj.optString("language", "")
                    val country = obj.optString("countrycode", "")
                    val rawUrl = obj.optString("url_resolved", obj.optString("url", "")).trim()
                    val bitrate = obj.optInt("bitrate", 64)
                    val uuid = obj.optString("stationuuid", java.util.UUID.randomUUID().toString())
                    
                    if (rawUrl.isNotBlank() && bitrate <= 128 && StationNormalizer.isArabicStation(name, lang, country)) {
                        list.add(StationEntity(uuid, name, wrapProxy(rawUrl), "", "", country, "NEWS", bitrate, 1, false, obj.optInt("clickcount", 100)))
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    private fun fetchFromIptvOrgArabic(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val url = "https://iptv-org.github.io/iptv/languages/ara.m3u"
        try {
            val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
            if (response.isSuccessful || response.code == 304) {
                var currentName = ""
                val lines = response.body?.string()?.lines() ?: emptyList()
                for (line in lines) {
                    if (line.startsWith("#EXTINF:")) {
                        currentName = line.substringAfterLast(",").trim()
                    } else if (line.startsWith("http") && currentName.isNotEmpty()) {
                        val isVideo = line.lowercase().contains(".m3u8") || line.lowercase().contains(".mp4") || line.lowercase().contains(".ts")
                        if (!isVideo) {
                            val uuid = "iptv_${currentName.hashCode()}_${line.hashCode()}"
                            list.add(StationEntity(uuid, currentName, wrapProxy(line), "", "", "AR", StationNormalizer.classify(uuid, currentName, ""), 64, 1, false, 60))
                        }
                        currentName = ""
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    private fun fetchFromEgyptM3U(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val url = "https://git.binarymagic.net/AIR/m3u-radio-music-playlists/raw/branch/master/+merged+/e/egypt.m3u"
        try {
            val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
            if (response.isSuccessful || response.code == 304) {
                var currentName = ""
                val lines = response.body?.string()?.lines() ?: emptyList()
                for (line in lines) {
                    if (line.startsWith("#EXTINF:")) {
                        currentName = line.substringAfterLast(",").trim()
                    } else if (line.startsWith("http") && currentName.isNotEmpty()) {
                        val uuid = "egypt_${currentName.hashCode()}_${line.hashCode()}"
                        list.add(StationEntity(uuid, currentName, wrapProxy(line), "", "", "EG", StationNormalizer.classify(uuid, currentName, ""), 64, 1, false, 85))
                        currentName = ""
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    // ✅ تم تصحيح بناء المحطات هنا باستخدام Named Arguments لضمان عدم حدوث أخطاء
    private fun getSeedStations(): List<StationEntity> {
        return listOf(
            StationEntity(
                uuid = "eg_quran_cairo", 
                name = "إذاعة القرآن الكريم - القاهرة", 
                primaryUrl = wrapProxy("https://stream.zeno.fm/f3wvbb1ch3quv"), 
                fallbackUrl = "", 
                favicon = "", 
                countryCode = "EG", 
                category = "QURAN", 
                bitrate = 32, 
                mirrorsCount = 1, 
                isFavorite = true, 
                popularityScore = 10000
            ),
            StationEntity(
                uuid = "eg_radio_masr", 
                name = "راديو مصر 88.7", 
                primaryUrl = wrapProxy("https://audio.nrpstream.com/listen/radio_masr/radio.mp3"), 
                fallbackUrl = "", 
                favicon = "", 
                countryCode = "EG", 
                category = "NEWS", 
                bitrate = 64, 
                mirrorsCount = 1, 
                isFavorite = true, 
                popularityScore = 9900
            ),
            StationEntity(
                uuid = "eg_nogoum_fm", 
                name = "نجوم إف إم 100.6", 
                primaryUrl = wrapProxy("https://audio.nrpstream.com/listen/nogoumfm/radio.mp3"), 
                fallbackUrl = "", 
                favicon = "", 
                countryCode = "EG", 
                category = "MUSIC", 
                bitrate = 128, 
                mirrorsCount = 1, 
                isFavorite = true, 
                popularityScore = 9950
            ),
            StationEntity(
                uuid = "eg_nile_fm", 
                name = "نايل إف إم 104.2", 
                primaryUrl = wrapProxy("https://audio.nrpstream.com/listen/nile_fm/radio.mp3"), 
                fallbackUrl = "", 
                favicon = "", 
                countryCode = "EG", 
                category = "MUSIC", 
                bitrate = 128, 
                mirrorsCount = 1, 
                isFavorite = true, 
                popularityScore = 9940
            ),
            StationEntity(
                uuid = "eg_9090_fm", 
                name = "راديو 9090", 
                primaryUrl = wrapProxy("https://9090streaming.mobtada.com/9090FMEGYPT"), 
                fallbackUrl = "", 
                favicon = "", 
                countryCode = "EG", 
                category = "NEWS", 
                bitrate = 64, 
                mirrorsCount = 1, 
                isFavorite = true, 
                popularityScore = 9850
            ),
            StationEntity(
                uuid = "eg_mega_fm", 
                name = "ميجا إف إم 92.7", 
                primaryUrl = wrapProxy("http://nebula.shoutca.st:8211/mp3"), 
                fallbackUrl = "", 
                favicon = "", 
                countryCode = "EG", 
                category = "MUSIC", 
                bitrate = 128, 
                mirrorsCount = 1, 
                isFavorite = true, 
                popularityScore = 9850
            ),
            StationEntity(
                uuid = "eg_sha3by_fm", 
                name = "شعبي إف إم 95.0", 
                primaryUrl = wrapProxy("https://radio95.radioca.st/"), 
                fallbackUrl = "", 
                favicon = "", 
                countryCode = "EG", 
                category = "MUSIC", 
                bitrate = 128, 
                mirrorsCount = 1, 
                isFavorite = true, 
                popularityScore = 9750
            ),
            StationEntity(
                uuid = "eg_nagham_fm", 
                name = "نغم إف إم 105.3", 
                primaryUrl = wrapProxy("https://ahmsamir.radioca.st/stream"), 
                fallbackUrl = "", 
                favicon = "", 
                countryCode = "EG", 
                category = "MUSIC", 
                bitrate = 128, 
                mirrorsCount = 1, 
                isFavorite = true, 
                popularityScore = 9800
            ),
            StationEntity(
                uuid = "ar_jazeera_voice", 
                name = "الجزيرة - صوت", 
                primaryUrl = wrapProxy("https://live-hls-audio-web-aja.getaj.net/VOICE-AJA/01.m3u8"), 
                fallbackUrl = "", 
                favicon = "", 
                countryCode = "QA", 
                category = "NEWS", 
                bitrate = 64, 
                mirrorsCount = 1, 
                isFavorite = true, 
                popularityScore = 9900
            ),
            StationEntity(
                uuid = "ar_alarabiya", 
                name = "العربية - صوت", 
                primaryUrl = wrapProxy("https://live.alarabiya.net/alarabiya/live.m3u8"), 
                fallbackUrl = "", 
                favicon = "", 
                countryCode = "SA", 
                category = "NEWS", 
                bitrate = 64, 
                mirrorsCount = 1, 
                isFavorite = true, 
                popularityScore = 9850
            ),
            StationEntity(
                uuid = "ar_bbc_arabic", 
                name = "بي بي سي عربي", 
                primaryUrl = wrapProxy("https://stream.live.vc.bbcmedia.co.uk/bbc_arabic_radio"), 
                fallbackUrl = "", 
                favicon = "", 
                countryCode = "GB", 
                category = "NEWS", 
                bitrate = 64, 
                mirrorsCount = 1, 
                isFavorite = true, 
                popularityScore = 9700
            )
        )
    }

    suspend fun toggleFavorite(uuid: String, currentStatus: Boolean) {
        stationDao.updateFavoriteStatus(uuid, !currentStatus)
    }
}
