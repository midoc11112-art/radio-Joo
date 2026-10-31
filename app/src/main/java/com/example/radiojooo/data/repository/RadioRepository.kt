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

    // ✅ الحل الثوري: supervisorScope + Timeout إجمالي 20 ثانية
    suspend fun refreshStations(): Int = withContext(Dispatchers.IO) {
        val fetchedList = mutableListOf<StationEntity>()

        // مهلة إجمالية 20 ثانية للتحديث بأكمله
        withTimeoutOrNull(20_000L) {
            supervisorScope {
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
                    async { fetchFromIslamicAPIUpdated() },
                    async { fetchFromCoranProM3U() },
                    async { fetchFromMp3QuranM3U() },
                    async { fetchFromArabicMusicM3U() },
                    async { fetchFromEgyptM3U() },
                    async { fetchFromIptvNews() },
                    async { fetchFromIptvArabic() },
                    async { fetchFromIPRDCountries() },
                    async { fetchFromBinaryMagicArabic() },
                    async { fetchFromBinaryMagicCountries() },
                    async { fetchFromIptvArabicAudioOnly() },
                    async { fetchFromIptvNewsAudioOnly() }
                )
                jobs.forEach { job ->
                    // مهلة فردية 10 ثوانٍ لكل مصدر
                    val result = withTimeoutOrNull(10_000L) { job.await() }
                    if (result != null) {
                        fetchedList.addAll(result)
                        // ✅ حفظ فوري في قاعدة البيانات بعد كل مصدر ناجح
                        if (fetchedList.isNotEmpty()) {
                            val distinct = fetchedList.distinctBy { it.uuid }
                            val currentFavs = stationDao.getStationsListDirect().filter { it.isFavorite }.map { it.uuid }.toSet()
                            val updated = distinct.map { it.copy(isFavorite = currentFavs.contains(it.uuid)) }
                            stationDao.insertStations(updated)
                        }
                    }
                }
            }
        }

        // حفظ نهائي (لأي نتائج إضافية)
        if (fetchedList.isNotEmpty()) {
            val distinctList = fetchedList.distinctBy { it.uuid }
            val currentFavs = stationDao.getStationsListDirect().filter { it.isFavorite }.map { it.uuid }.toSet()
            val updated = distinctList.map { it.copy(isFavorite = currentFavs.contains(it.uuid)) }
            stationDao.insertStations(updated)
            return@withContext updated.size
        } else {
            // إذا فشل كل شيء، أضف محطات الطوارئ
            insertEmergencyStations()
            return@withContext stationDao.getStationsCount()
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
