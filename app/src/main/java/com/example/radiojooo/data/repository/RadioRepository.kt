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

class RadioRepository(private val stationDao: StationDao, cacheDir: File) {

    private val PROXY_BASE_URL = "https://radio-joo-proxy.midoc11112.workers.dev/?stream_url="

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
            // عرض المحطات المخزنة فوراً
            if (stationDao.getStationsCount() == 0) {
                stationDao.insertStations(getSeedStations())
            }
            // التحديث في الخلفية يحدث بصمت تام ولا يظهر Spinner
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
            
            // الحفظ التدريجي: كل مصدر ينتهي يتم حفظه فوراً
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
                    val rawUrl = obj.optString("url_resolved", obj.optString("url", "")).trim()
                    val bitrate = obj.optInt("bitrate", 64)
                    val uuid = obj.optString("stationuuid", java.util.UUID.randomUUID().toString())
                    
                    if (rawUrl.isNotBlank() && bitrate <= 128 && StationNormalizer.isArabicStation(name, lang)) {
                        val category = StationNormalizer.classify(uuid, name, "")
                        list.add(StationEntity(uuid, name, wrapProxy(rawUrl), "", "", "EG", category, "EG", bitrate, false, obj.optInt("clickcount", 100)))
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
                    val rawUrl = obj.optString("url_resolved", obj.optString("url", "")).trim()
                    val bitrate = obj.optInt("bitrate", 64)
                    val country = obj.optString("countrycode", "AR")
                    val uuid = obj.optString("stationuuid", java.util.UUID.randomUUID().toString())
                    
                    if (rawUrl.isNotBlank() && bitrate <= 128 && StationNormalizer.isArabicStation(name, lang)) {
                        val category = StationNormalizer.classify(uuid, name, "")
                        list.add(StationEntity(uuid, name, wrapProxy(rawUrl), "", "", country, category, country, bitrate, false, obj.optInt("clickcount", 100)))
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
                    val rawUrl = obj.optString("url_resolved", obj.optString("url", "")).trim()
                    val bitrate = obj.optInt("bitrate", 64)
                    val country = obj.optString("countrycode", "AR")
                    val uuid = obj.optString("stationuuid", java.util.UUID.randomUUID().toString())
                    
                    if (rawUrl.isNotBlank() && bitrate <= 128 && StationNormalizer.isArabicStation(name, lang)) {
                        list.add(StationEntity(uuid, name, wrapProxy(rawUrl), "", "", country, "NEWS", country, bitrate, false, obj.optInt("clickcount", 100)))
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
                        if (!isVideo && StationNormalizer.isArabicStation(currentName, "arabic")) {
                            val uuid = "iptv_${currentName.hashCode()}_${line.hashCode()}"
                            list.add(StationEntity(uuid, currentName, wrapProxy(line), "", "", "AR", StationNormalizer.classify(uuid, currentName, ""), "AR", 64, false, 60))
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
                        if (StationNormalizer.isArabicStation(currentName, "")) {
                            val uuid = "egypt_${currentName.hashCode()}_${line.hashCode()}"
                            list.add(StationEntity(uuid, currentName, wrapProxy(line), "", "", "EG", StationNormalizer.classify(uuid, currentName, ""), "EG", 64, false, 85))
                        }
                        currentName = ""
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    private fun getSeedStations(): List<StationEntity> {
        return listOf(
            StationEntity("eg_quran_cairo", "إذاعة القرآن الكريم - القاهرة", wrapProxy("https://stream.zeno.fm/f3wvbb1ch3quv"), "", "", "EG", "QURAN", "EG", 32, true, 10000),
            StationEntity("eg_radio_masr", "راديو مصر 88.7", wrapProxy("https://audio.nrpstream.com/listen/radio_masr/radio.mp3"), "", "", "EG", "NEWS", "EG", 64, true, 9900),
            StationEntity("eg_nogoum_fm", "نجوم إف إم 100.6", wrapProxy("https://audio.nrpstream.com/listen/nogoumfm/radio.mp3"), "", "", "EG", "MUSIC", "EG", 128, true, 9950),
            StationEntity("eg_nile_fm", "نايل إف إم 104.2", wrapProxy("https://audio.nrpstream.com/listen/nile_fm/radio.mp3"), "", "", "EG", "MUSIC", "EG", 128, true, 9940),
            StationEntity("eg_9090_fm", "راديو 9090", wrapProxy("https://9090streaming.mobtada.com/9090FMEGYPT"), "", "", "EG", "NEWS", "EG", 64, true, 9850),
            StationEntity("eg_mega_fm", "ميجا إف إم 92.7", wrapProxy("http://nebula.shoutca.st:8211/mp3"), "", "", "EG", "MUSIC", "EG", 128, true, 9850),
            StationEntity("eg_sha3by_fm", "شعبي إف إم 95.0", wrapProxy("https://radio95.radioca.st/"), "", "", "EG", "MUSIC", "EG", 128, true, 9750),
            StationEntity("eg_nagham_fm", "نغم إف إم 105.3", wrapProxy("https://ahmsamir.radioca.st/stream"), "", "", "EG", "MUSIC", "EG", 128, true, 9800),
            StationEntity("ar_jazeera_voice", "الجزيرة - صوت", wrapProxy("https://live-hls-audio-web-aja.getaj.net/VOICE-AJA/01.m3u8"), "", "", "QA", "NEWS", "QA", 64, true, 9900),
            StationEntity("ar_alarabiya", "العربية - صوت", wrapProxy("https://live.alarabiya.net/alarabiya/live.m3u8"), "", "", "SA", "NEWS", "SA", 64, true, 9850),
            StationEntity("ar_bbc_arabic", "بي بي سي عربي", wrapProxy("https://stream.live.vc.bbcmedia.co.uk/bbc_arabic_radio"), "", "", "GB", "NEWS", "GB", 64, true, 9700)
        )
    }

    suspend fun toggleFavorite(uuid: String, currentStatus: Boolean) {
        stationDao.updateFavoriteStatus(uuid, !currentStatus)
    }
}
