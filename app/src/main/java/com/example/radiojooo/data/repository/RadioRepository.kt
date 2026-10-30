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

    private val PROXY_BASE_URL = "https://radio-joo-proxy.midoc11112.workers.dev/?url="

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
                insertSeedStations()
                refreshStations()
            }
        }
    }

    suspend fun refreshStations(): Int = withContext(Dispatchers.IO) {
        var insertedCount = 0
        val fetchedList = mutableListOf<StationEntity>()

        val limit = 300
        val offsets = listOf(0)

        for (offset in offsets) {
            val pageUrls = listOf(
                "https://de1.api.radio-browser.info/json/stations/search?countrycode=EG&limit=$limit&offset=$offset",
                "https://de1.api.radio-browser.info/json/stations/search?language=arabic&limit=$limit&offset=$offset"
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
        } else {
            insertSeedStations()
        }

        insertedCount
    }

    private suspend fun insertSeedStations() {
        val seedList = listOf(
            StationEntity(uuid = "eg_nogoom", name = "نجوم إف إم 100.6", primaryUrl = wrapProxy("https://nrp.streamguys1.com/nogoumfm"), fallbackUrl = "", category = "MUSIC", countryCode = "EG", bitrate = 64, isFavorite = false, popularityScore = 1000),
            StationEntity(uuid = "eg_quran", name = "إذاعة القرآن الكريم من القاهرة", primaryUrl = wrapProxy("https://stream.radiojar.com/8s44vhq972ztv"), fallbackUrl = "", category = "QURAN", countryCode = "EG", bitrate = 32, isFavorite = true, popularityScore = 999),
            StationEntity(uuid = "eg_nile", name = "راديو مصر 88.7", primaryUrl = wrapProxy("https://stream.radiojar.com/ud77vhq972ztv"), fallbackUrl = "", category = "NEWS", countryCode = "EG", bitrate = 32, isFavorite = false, popularityScore = 950),
            StationEntity(uuid = "eg_9090", name = "الراديو 9090 FM", primaryUrl = wrapProxy("https://9090streaming.mobtada.com/9090FMEGYPT"), fallbackUrl = "", category = "EGYPT", countryCode = "EG", bitrate = 64, isFavorite = false, popularityScore = 940),
            StationEntity(uuid = "eg_mega", name = "ميجا إف إم 92.7", primaryUrl = wrapProxy("https://stream.radiojar.com/3b88vhq972ztv"), fallbackUrl = "", category = "MUSIC", countryCode = "EG", bitrate = 32, isFavorite = false, popularityScore = 930),
            StationEntity(uuid = "eg_nagham", name = "نغم إف إم 105.3", primaryUrl = wrapProxy("https://stream.radiojar.com/5c99vhq972ztv"), fallbackUrl = "", category = "MUSIC", countryCode = "EG", bitrate = 32, isFavorite = false, popularityScore = 920),
            StationEntity(uuid = "eg_shaaby", name = "شعبي إف إم 95.0", primaryUrl = wrapProxy("https://stream.radiojar.com/6d00vhq972ztv"), fallbackUrl = "", category = "MUSIC", countryCode = "EG", bitrate = 32, isFavorite = false, popularityScore = 910),
            StationEntity(uuid = "eg_bbc_ar", name = "بي بي سي عربي", primaryUrl = wrapProxy("https://stream.live.vc.bbcmedia.co.uk/bbc_arabic_radio"), fallbackUrl = "", category = "NEWS", countryCode = "GB", bitrate = 32, isFavorite = false, popularityScore = 900),
            StationEntity(uuid = "eg_monte", name = "مونت كارلو الدولية", primaryUrl = wrapProxy("https://montecarlodoualiya.ice.infomaniak.ch/mc-doualiya-mid.mp3"), fallbackUrl = "", category = "NEWS", countryCode = "FR", bitrate = 32, isFavorite = false, popularityScore = 890)
        )
        stationDao.insertStations(seedList)
    }

    suspend fun toggleFavorite(uuid: String, currentStatus: Boolean) {
        stationDao.updateFavoriteStatus(uuid, !currentStatus)
    }
}
