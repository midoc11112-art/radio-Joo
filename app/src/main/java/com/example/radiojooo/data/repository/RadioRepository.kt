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
            // 1. إضافة المحطات المضمونة فوراً إذا كانت قاعدة البيانات فارغة
            if (stationDao.getStationsCount() == 0) {
                stationDao.insertStations(getSeedStations())
            }
            // 2. محاولة التحديث من المصادر في الخلفية (لا تمسح المحطات المضمونة إذا فشلت)
            refreshStations()
        }
    }

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
            // دمج المحطات الجديدة مع المحطات المضمنة (لا نمسح المضمنة)
            stationDao.insertStations(updated)
            stationDao.getStationsCount()
        } else {
            // إذا فشل التحديث، نعيد عدد المحطات الموجودة (بما فيها المضمنة)
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
                connection.setRequestProperty("User-Agent", "RadioJooApp/13.0")
                if (connection.responseCode == 200) {
                    val lines = connection.inputStream.bufferedReader().use { it.readLines() }
                    var currentName = ""
                    for (line in lines) {
                        if (line.startsWith("#EXTINF:")) {
                            currentName = line.substringAfterLast(",").trim()
                        } else if (line.startsWith("http") && currentName.isNotEmpty()) {
                            val lowerUrl = line.lowercase()
                            val lowerName = currentName.lowercase()
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
                                        bitrate = 64,
                                        isFavorite = false,
                                        popularityScore = 80
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

    // ==================== 2. عربي (مع bitrate صريح) ====================
    private fun fetchFromArabicM3U(): List<StationEntity> {
        val list = mutableListOf<StationEntity>()
        val url = "https://git.binarymagic.net/AIR/m3u-radio-music-playlists/raw/branch/master/arabic.m3u"
        try {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "RadioJooApp/13.0")
            if (connection.responseCode == 200) {
                val lines = connection.inputStream.bufferedReader().use { it.readLines() }
                var currentName = ""
                for (line in lines) {
                    if (line.startsWith("#EXTINF:")) {
                        currentName = line.substringAfterLast(",").trim()
                    } else if (line.startsWith("http") && currentName.isNotEmpty()) {
                        val bitrateMatch = Regex("(\\d+)\\s*kbit/s").find(currentName)
                        val bitrate = bitrateMatch?.groupValues?.get(1)?.toIntOrNull() ?: 128
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

    // ==================== 3. قائمة المحطات المضمونة (Seed Stations) ====================
    private fun getSeedStations(): List<StationEntity> {
        return listOf(
            // --- محطات مصرية ---
            StationEntity("eg_quran_cairo", "إذاعة القرآن الكريم - القاهرة", wrapProxy("https://stream.zeno.fm/f3wvbb1ch3quv"), "", "quran", "EG", "QURAN", 32, true, 10000),
            StationEntity("eg_radio_masr", "راديو مصر 88.7", wrapProxy("https://audio.nrpstream.com/listen/radio_masr/radio.mp3"), "", "news", "EG", "NEWS", 64, true, 9900),
            StationEntity("eg_nogoum_fm", "نجوم إف إم 100.6", wrapProxy("https://audio.nrpstream.com/listen/nogoumfm/radio.mp3"), "", "music", "EG", "MUSIC", 128, true, 9950),
            StationEntity("eg_nile_fm", "نايل إف إم 104.2", wrapProxy("https://audio.nrpstream.com/listen/nile_fm/radio.mp3"), "", "music", "EG", "MUSIC", 128, true, 9940),
            StationEntity("eg_9090_fm", "راديو 9090", wrapProxy("https://9090streaming.mobtada.com/9090FMEGYPT"), "", "news", "EG", "NEWS", 64, true, 9850),
            StationEntity("eg_mega_fm", "ميجا إف إم 92.7", wrapProxy("http://nebula.shoutca.st:8211/mp3"), "", "music", "EG", "MUSIC", 128, true, 9850),
            StationEntity("eg_sha3by_fm", "شعبي إف إم 95.0", wrapProxy("https://radio95.radioca.st/"), "", "music", "EG", "MUSIC", 128, true, 9750),
            StationEntity("eg_nagham_fm", "نغم إف إم 105.3", wrapProxy("https://ahmsamir.radioca.st/stream"), "", "music", "EG", "MUSIC", 128, true, 9800),
            StationEntity("eg_banha_city", "بنها سيتي", wrapProxy("https://stream.zeno.fm/6d00vhq972ztv"), "", "general", "EG", "GENERAL", 64, false, 60),
            StationEntity("eg_diab_fm", "دياب إف إم", wrapProxy("https://stream.zeno.fm/3b88vhq972ztv"), "", "general", "EG", "GENERAL", 64, false, 60),
            // --- محطات عربية ---
            StationEntity("ar_jazeera_voice", "الجزيرة - صوت", wrapProxy("https://live-hls-audio-web-aja.getaj.net/VOICE-AJA/01.m3u8"), "", "news", "QA", "NEWS", 64, true, 9900),
            StationEntity("ar_alarabiya", "العربية - صوت", wrapProxy("https://live.alarabiya.net/alarabiya/live.m3u8"), "", "news", "SA", "NEWS", 64, true, 9850),
            StationEntity("ar_bbc_arabic", "بي بي سي عربي", wrapProxy("https://stream.live.vc.bbcmedia.co.uk/bbc_arabic_radio"), "", "news", "GB", "NEWS", 64, true, 9700),
            StationEntity("ar_monte_carlo", "مونت كارلو الدولية", wrapProxy("https://montecarlodoualiya.ice.infomaniak.ch/mc-doualiya-mid.mp3"), "", "news", "FR", "NEWS", 64, true, 890),
            StationEntity("ar_sky_news", "سكاي نيوز عربية", wrapProxy("https://stream.skynewsarabia.com/hls/sna.m3u8"), "", "news", "AE", "NEWS", 64, false, 880),
            StationEntity("ar_alhadath", "الحدث - صوت", wrapProxy("https://live-hls-audio-web-ala.getaj.net/VOICE-ALA/01.m3u8"), "", "news", "AE", "NEWS", 64, false, 870),
            StationEntity("ar_sputnik", "سبوتنيك عربي", wrapProxy("https://stream.zeno.fm/z988y8k9h8quv"), "", "news", "RU", "NEWS", 64, false, 860),
            StationEntity("ar_mbc_fm", "MBC FM", wrapProxy("https://stream.zeno.fm/4v85y8k9h8quv"), "", "music", "SA", "MUSIC", 64, false, 850),
            StationEntity("ar_rotana", "روتانا FM", wrapProxy("https://stream.zeno.fm/7x3b77bh3quv"), "", "music", "SA", "MUSIC", 64, false, 840),
            StationEntity("ar_quran_makkah", "إذاعة القرآن الكريم - مكة", wrapProxy("https://qurango.net/radio/makkah"), "", "quran", "SA", "QURAN", 64, false, 830)
        )
    }

    suspend fun toggleFavorite(uuid: String, currentStatus: Boolean) {
        stationDao.updateFavoriteStatus(uuid, !currentStatus)
    }
}
