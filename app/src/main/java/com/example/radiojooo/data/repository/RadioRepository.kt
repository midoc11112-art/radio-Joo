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
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.StringReader
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class RadioRepository(private val stationDao: StationDao, private val cacheDir: File) {

    // ✅ الرابط الأصلي لسيرفرك
    private val PROXY_BASE_URL = "https://radio-joo-proxy.midoc11112.workers.dev/?url="

    private val failoverInterceptor = Interceptor { chain ->
        val originalRequest = chain.request()
        try {
            val response = chain.proceed(originalRequest)
            if (response.isSuccessful || response.code == 304) {
                response
            } else if (response.code in listOf(502, 503, 504)) {
                response.close()
                val retryRequest = originalRequest.newBuilder().header("X-Retry-Attempt", "1").build()
                chain.proceed(retryRequest)
            } else {
                response
            }
        } catch (e: IOException) {
            if (originalRequest.header("X-Retry-Attempt") == null) {
                val retryRequest = originalRequest.newBuilder().header("X-Retry-Attempt", "1").build()
                chain.proceed(retryRequest)
            } else {
                throw e
            }
        }
    }

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .cache(Cache(File(cacheDir, "radio_http_cache"), 10L * 1024 * 1024))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(failoverInterceptor)
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
        }
    }

    suspend fun refreshStations(): Int = withContext(Dispatchers.IO) {
        try {
            supervisorScope {
                val jobs = listOf(
                    async { withTimeoutOrNull(15_000L) { fetchFromRadioBrowser("bycountrycodeexact/EG", "EG", null) } },
                    async { withTimeoutOrNull(15_000L) { fetchFromRadioBrowser("bylanguage/arabic", null, null) } },
                    async { withTimeoutOrNull(15_000L) { fetchFromRadioBrowser("bytag/news", null, "NEWS") } },
                    async { withTimeoutOrNull(15_000L) { fetchFromRadioBrowser("bytag/talkshow", null, "NEWS") } },
                    async { withTimeoutOrNull(15_000L) { fetchFromRadioBrowser("bytag/quran", null, "QURAN") } },
                    async { withTimeoutOrNull(15_000L) { fetchFromIptvOrgArabic() } },
                    async { withTimeoutOrNull(15_000L) { fetchFromBinaryMagic() } },
                    async { withTimeoutOrNull(15_000L) { fetchFromIslamicRadioApi() } },
                    async { withTimeoutOrNull(15_000L) { fetchFromTuneInOpml() } }
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
        stationDao.getStationsCount()
    }

    suspend fun validateAndHealStations(): Int = 0

    private fun fetchFromRadioBrowser(
        endpoint: String,
        countryCode: String? = null,
        forcedCategory: String? = null
    ): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val url = "https://de1.api.radio-browser.info/json/stations/$endpoint?limit=1000"
        try {
            val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
            if (response.isSuccessful || response.code == 304) {
                val jsonArray = JSONArray(response.body?.string() ?: "[]")
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val name = obj.optString("name", "محطة").trim()
                    val lang = obj.optString("language", "")
                    val country = obj.optString("countrycode", countryCode ?: "")
                    val rawUrl = obj.optString("url_resolved", obj.optString("url", "")).trim()
                    val bitrate = obj.optInt("bitrate", 64)
                    val uuid = obj.optString("stationuuid", java.util.UUID.randomUUID().toString())

                    if (rawUrl.isNotBlank() && StationNormalizer.isArabicStation(name, lang, country)) {
                        val category = forcedCategory ?: StationNormalizer.classify(uuid, name, "")
                        list.add(StationEntity(uuid, name, wrapProxy(rawUrl), "", "", country, category, country, bitrate, false, obj.optInt("clickcount", 100)))
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
                        val uuid = "iptv_${currentName.hashCode()}_${line.hashCode()}"
                        list.add(StationEntity(uuid, currentName, wrapProxy(line), "", "", "AR", StationNormalizer.classify(uuid, currentName, ""), "AR", 64, false, 60))
                        currentName = ""
                    }
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    private fun fetchFromBinaryMagic(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val urls = listOf(
            "https://git.binarymagic.net/AIR/m3u-radio-music-playlists/raw/branch/master/+merged+/e/egypt.m3u",
            "https://git.binarymagic.net/AIR/m3u-radio-music-playlists/raw/branch/master/+merged+/a/arabic_(syria).m3u"
        )
        for (url in urls) {
            try {
                val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
                if (response.isSuccessful || response.code == 304) {
                    var currentName = ""
                    val lines = response.body?.string()?.lines() ?: emptyList()
                    for (line in lines) {
                        if (line.startsWith("#EXTINF:")) {
                            currentName = line.substringAfterLast(",").trim()
                        } else if (line.startsWith("http") && currentName.isNotEmpty()) {
                            val uuid = "bm_${currentName.hashCode()}_${line.hashCode()}"
                            list.add(StationEntity(uuid, currentName, wrapProxy(line), "", "", "AR", StationNormalizer.classify(uuid, currentName, ""), "AR", 64, false, 80))
                            currentName = ""
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        return list
    }

    private fun fetchFromIslamicRadioApi(): List<StationEntity> {
        val candidateUrls = listOf(
            "https://uthumany.github.io/islamic-radio-api/radios.json",
            "https://raw.githubusercontent.com/uthumany/islamic-radio-api/main/radios.json"
        )
        for (url in candidateUrls) {
            try {
                val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: continue
                    val parsed = parseIslamicRadioJson(bodyStr)
                    if (parsed.isNotEmpty()) return parsed
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        return emptyList()
    }

    private fun parseIslamicRadioJson(jsonStr: String): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        try {
            val jsonArray: JSONArray = try {
                JSONArray(jsonStr)
            } catch (e: Exception) {
                val obj = JSONObject(jsonStr)
                when {
                    obj.has("radios") -> obj.getJSONArray("radios")
                    obj.has("data") -> obj.getJSONArray("data")
                    obj.has("stations") -> obj.getJSONArray("stations")
                    else -> JSONArray()
                }
            }
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").ifBlank { obj.optString("title", "") }.trim()
                val rawUrl = obj.optString("url", "").ifBlank { obj.optString("stream", "") }.ifBlank { obj.optString("streamUrl", "") }.trim()
                if (name.isBlank() || rawUrl.isBlank()) continue
                val uuid = "islamic_${name.hashCode()}_${rawUrl.hashCode()}"
                list.add(StationEntity(uuid, name, wrapProxy(rawUrl), "", "", "EG", "QURAN", "EG", 48, false, 95))
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    private fun fetchFromTuneInOpml(): List<StationEntity> {
        val candidateUrls = listOf(
            "https://opml.radiotime.com/Search.ashx?query=arabic&render=opml",
            "https://opml.radiotime.com/Browse.ashx?render=opml"
        )
        for (url in candidateUrls) {
            try {
                val response = okHttpClient.newCall(Request.Builder().url(url).build()).execute()
                if (response.isSuccessful || response.code == 304) {
                    val body = response.body?.string() ?: continue
                    val parsed = parseTuneInOpml(body)
                    if (parsed.isNotEmpty()) return parsed
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        return emptyList()
    }

    private fun parseTuneInOpml(opml: String): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        try {
            val parser = android.util.Xml.newPullParser()
            parser.setInput(StringReader(opml))
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG && parser.name == "outline") {
                    val text = parser.getAttributeValue(null, "text") ?: ""
                    val streamUrl = parser.getAttributeValue(null, "URL") ?: ""
                    if (text.isNotBlank() && streamUrl.isNotBlank() && StationNormalizer.isArabicStation(text, "", "")) {
                        val uuid = "tunein_${text.hashCode()}_${streamUrl.hashCode()}"
                        list.add(StationEntity(uuid, text, wrapProxy(streamUrl), "", "", "AR", StationNormalizer.classify(uuid, text, ""), "AR", 64, false, 70))
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) { e.printStackTrace() }
        return list
    }

    private fun getSeedStations(): List<StationEntity> {
        return listOf(
            StationEntity("eg_quran_cairo", "إذاعة القرآن الكريم - القاهرة", wrapProxy("https://stream.zeno.fm/f3wvbb1ch3quv"), "", "", "EG", "QURAN", "EG", 32, true, 10000),
            StationEntity("eg_radio_masr", "راديو مصر 88.7", wrapProxy("https://audio.nrpstream.com/listen/radio_masr/radio.mp3"), "", "", "EG", "NEWS", "EG", 64, true, 9900),
            StationEntity("eg_9090_fm", "راديو 9090", wrapProxy("https://9090streaming.mobtada.com/9090FMEGYPT"), "", "", "EG", "NEWS", "EG", 64, true, 9850),
            StationEntity("ar_jazeera_voice", "الجزيرة - صوت", wrapProxy("https://live-hls-audio-web-aja.getaj.net/VOICE-AJA/01.m3u8"), "", "", "QA", "NEWS", "QA", 128, true, 9900),
            StationEntity("ar_bbc_arabic", "بي بي سي عربي", wrapProxy("https://stream.live.vc.bbcmedia.co.uk/bbc_arabic_radio"), "", "", "GB", "NEWS", "GB", 56, true, 9700)
        )
    }

    suspend fun toggleFavorite(uuid: String, currentStatus: Boolean) {
        stationDao.updateFavoriteStatus(uuid, !currentStatus)
    }
}
