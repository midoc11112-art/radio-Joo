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

    // ✅ الحل الثوري: جلب متوازي، مهلة 8 ثوانٍ لكل مصدر، حفظ واحد فقط
    suspend fun refreshStations(): Int = withContext(Dispatchers.IO) {
        val fetchedList = mutableListOf<StationEntity>()

        // مهلة إجمالية 15 ثانية للتحديث بأكمله
        withTimeoutOrNull(15_000L) {
            supervisorScope {
                val jobs = listOf(
                    async { withTimeoutOrNull(8_000L) { fetchFromRadioBrowserAllArab() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromRadioBrowserByTags() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromIPRD() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromIslamicAPI() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromM3U() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromTuneIn() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromIHeart() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromRadioGarden() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromMp3Quran() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromCountryM3U() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromIptvOrg() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromM3URadioMusic() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromRadioPlayer() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromIslamicAPIUpdated() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromCoranProM3U() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromMp3QuranM3U() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromArabicMusicM3U() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromEgyptM3U() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromIptvNews() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromIptvArabic() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromIPRDCountries() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromBinaryMagicArabic() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromBinaryMagicCountries() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromIptvArabicAudioOnly() } },
                    async { withTimeoutOrNull(8_000L) { fetchFromIptvNewsAudioOnly() } }
                )
                // awaitAll يعيد قائمة من النتائج (بما فيها null)
                val results = jobs.awaitAll()
                results.filterNotNull().forEach { list ->
                    fetchedList.addAll(list)
                }
            }
        }

        // ✅ حفظ واحد فقط بعد جمع كل النتائج
        if (fetchedList.isNotEmpty()) {
            val distinctList = fetchedList.distinctBy { it.uuid }
            val currentFavs = stationDao.getStationsListDirect().filter { it.isFavorite }.map { it.uuid }.toSet()
            val updated = distinctList.map { it.copy(isFavorite = currentFavs.contains(it.uuid)) }
            stationDao.insertStations(updated)
            updated.size
        } else {
            // إذا فشل كل شيء، أضف محطات الطوارئ
            insertEmergencyStations()
            stationDao.getStationsCount()
        }
    }

    // ... (جميع دوال الجلب تبقى كما هي تماماً، دون أي تغيير)
    // سأدرجها هنا مختصرة للتوضيح، لكن في الكود الفعلي تبقى كاملة.

    private fun fetchFromRadioBrowserAllArab(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromRadioBrowserByTags(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromIPRD(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromIslamicAPI(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromM3U(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromTuneIn(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromIHeart(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromRadioGarden(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromMp3Quran(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromCountryM3U(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromIptvOrg(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromM3URadioMusic(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromRadioPlayer(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromIslamicAPIUpdated(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromCoranProM3U(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromMp3QuranM3U(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromArabicMusicM3U(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromEgyptM3U(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromIptvNews(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromIptvArabic(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromIPRDCountries(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromBinaryMagicArabic(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromBinaryMagicCountries(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromIptvArabicAudioOnly(): List<StationEntity> { /* ... */ return emptyList() }
    private fun fetchFromIptvNewsAudioOnly(): List<StationEntity> { /* ... */ return emptyList() }
    private fun resolveTuneInStream(tuneUrl: String): List<Pair<String, Int>> { /* ... */ return emptyList() }

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
