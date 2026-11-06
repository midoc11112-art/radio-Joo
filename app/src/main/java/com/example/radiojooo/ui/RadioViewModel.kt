package com.example.radiojooo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.radiojooo.data.local.AppDatabase
import com.example.radiojooo.data.model.StationEntity
import com.example.radiojooo.data.repository.HealthCheckResult
import com.example.radiojooo.data.repository.RadioRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

data class CategoryCounts(
    val allCount: Int = 0, val favCount: Int = 0, val egyptCount: Int = 0,
    val quranCount: Int = 0, val newsCount: Int = 0, val musicCount: Int = 0,
    val sportsCount: Int = 0, val talkCount: Int = 0
)

class RadioViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: RadioRepository
    val allRawStations: StateFlow<List<StationEntity>>
    val searchQuery = MutableStateFlow("")
    val selectedCategory = MutableStateFlow("ALL")
    val healthChecked: StateFlow<Int> get() = repository.healthChecked
    val healthTotal: StateFlow<Int> get() = repository.healthTotal
    val healthComplete: SharedFlow<HealthCheckResult> get() = repository.healthComplete

    private val _currentPlayingStation = MutableStateFlow<StationEntity?>(null)
    val currentPlayingStation: StateFlow<StationEntity?> = _currentPlayingStation.asStateFlow()
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _refreshResult = MutableSharedFlow<Int>()
    val refreshResult: SharedFlow<Int> = _refreshResult.asSharedFlow()
    val categoryCounts = MutableStateFlow(CategoryCounts())
    val stations: StateFlow<List<StationEntity>>

    private val normalizedNameCache = ConcurrentHashMap<String, String>()

    companion object {
        private val TASHKEEL_REGEX = Regex("[\\u0610-\\u061A\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
        private val ALEF_VARIANTS = Regex("[إأآٱ]")
        private val NON_ALNUM = Regex("[^\\u0600-\\u06FFa-zA-Z0-9\\s]")
        private val MULTI_SPACE = Regex("\\s+")
        private val NUM_STRIP_GREATER = Regex("اكبر|من")
        private val NUM_STRIP_LESS = Regex("اصغر|من")

        fun normalizeArabic(input: String?): String {
            if (input.isNullOrBlank()) return ""
            return input
                .replace(TASHKEEL_REGEX, "")
                .replace("ـ", "")
                .replace(ALEF_VARIANTS, "ا")
                .replace("ة", "ه")
                .replace("ى", "ي")
                .replace("ؤ", "و")
                .replace("ئ", "ي")
                .replace(NON_ALNUM, " ")
                .replace(MULTI_SPACE, " ")
                .trim()
                .lowercase()
        }
    }

    init {
        val stationDao = AppDatabase.getDatabase(application).stationDao()
        repository = RadioRepository(application, stationDao, application.cacheDir)
        val rawFlow = MutableStateFlow<List<StationEntity>>(emptyList())
        allRawStations = rawFlow.asStateFlow()

        viewModelScope.launch {
            repository.allStations.collect { list ->
                for (st in list) {
                    normalizedNameCache.computeIfAbsent(st.uuid) { normalizeArabic(st.name) }
                }
                rawFlow.value = list
                updateCategoryCounts(list)
            }
        }
        viewModelScope.launch(Dispatchers.IO) { repository.ensureInitialDataLoaded() }

        val filteredFlow = MutableStateFlow<List<StationEntity>>(emptyList())
        stations = filteredFlow.asStateFlow()

        viewModelScope.launch {
            combine(allRawStations, searchQuery, selectedCategory) { list, query, category ->
                var filtered = list
                if (category != "ALL") {
                    filtered = when (category) {
                        "FAVORITE" -> filtered.filter { it.isFavorite }
                        "EGYPT" -> filtered.filter { it.countryCode.equals("EG", ignoreCase = true) || it.category.equals("EGYPT", ignoreCase = true) }
                        else -> filtered.filter { it.category.equals(category, ignoreCase = true) }
                    }
                }
                if (query.isNotBlank()) {
                    val qRaw = query.trim().lowercase()
                    val hasGT = qRaw.contains(">")
                    val hasLT = qRaw.contains("<")
                    val q = normalizeArabic(qRaw)
                    val isGreater = q.contains("اكبر") || hasGT
                    val isLess = q.contains("اصغر") || hasLT

                    if (isGreater) {
                        val numStr = q.replace(NUM_STRIP_GREATER, "").trim()
                        val bitrateNum = numStr.toIntOrNull()
                        if (bitrateNum != null) filtered = filtered.filter { it.bitrate > bitrateNum && it.bitrate > 0 }
                        else filtered = emptyList()
                    } else if (isLess) {
                        val numStr = q.replace(NUM_STRIP_LESS, "").trim()
                        val bitrateNum = numStr.toIntOrNull()
                        if (bitrateNum != null) filtered = filtered.filter { it.bitrate in 1 until bitrateNum }
                        else filtered = emptyList()
                    } else {
                        val bitrateNum = q.toIntOrNull()
                        val countryCodes = getCountryCodesForQuery(q)
                        val matchedCategory = getCategoryForQuery(q)
                        filtered = filtered.filter { station ->
                            if (bitrateNum != null && station.bitrate == bitrateNum) return@filter true
                            val nameNorm = normalizedNameCache.computeIfAbsent(station.uuid) { normalizeArabic(station.name) }
                            if (nameNorm.contains(q)) return@filter true
                            if (fuzzyWordMatch(q, nameNorm)) return@filter true
                            val catNorm = normalizeArabic(station.category)
                            if (catNorm.contains(q)) return@filter true
                            if (station.countryCode.lowercase().contains(q)) return@filter true
                            if (matchedCategory != null && station.category.equals(matchedCategory, ignoreCase = true)) return@filter true
                            if (countryCodes.isNotEmpty() && countryCodes.contains(station.countryCode.uppercase())) return@filter true
                            false
                        }
                    }
                }
                // v109.3: الترتيب حسب priority أولاً ثم popularity
                filtered.sortedWith(compareByDescending<StationEntity> { it.priority }.thenByDescending { it.popularity })
            }.collect { filteredFlow.value = it }
        }
    }

    private fun fuzzyWordMatch(query: String, target: String): Boolean {
        if (query.length < 3) return false
        val words = target.split(" ").filter { it.isNotBlank() }
        val threshold = maxOf(1, query.length / 3)
        for (w in words) {
            if (w == query) return true
            if (levenshteinDistance(query, w) <= threshold) return true
        }
        return false
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
        }
        return dp[a.length][b.length]
    }

    private fun getCountryCodesForQuery(q: String): List<String> {
        val eg = listOf("مصر", "egypt", "masr", "eg")
        val sa = listOf("السعوديه", "saudi", "ksa", "sa")
        val ae = listOf("الامارات", "uae", "emirates", "ae")
        val kw = listOf("الكويت", "kuwait", "kw")
        val qa = listOf("قطر", "qatar", "qa")
        val bh = listOf("البحرين", "bahrain", "bh")
        val om = listOf("عمان", "oman", "om")
        val jo = listOf("الاردن", "jordan", "jo")
        val lb = listOf("لبنان", "lebanon", "lb")
        val sy = listOf("سوريا", "syria", "sy")
        val iq = listOf("العراق", "iraq", "iq")
        val ps = listOf("فلسطين", "palestine", "ps")
        val ye = listOf("اليمن", "yemen", "ye")
        val sd = listOf("السودان", "sudan", "sd")
        val ly = listOf("ليبيا", "libya", "ly")
        val tn = listOf("تونس", "tunisia", "tn")
        val dz = listOf("الجزائر", "algeria", "dz")
        val ma = listOf("المغرب", "morocco", "ma")
        val mr = listOf("موريتانيا", "mauritania", "mr")
        val fr = listOf("فرنسا", "france", "fr")
        val uk = listOf("بريطانيا", "uk", "gb", "england")
        val de = listOf("المانيا", "germany", "de")
        val us = listOf("امريكا", "usa", "us")
        return when {
            eg.contains(q) -> listOf("EG"); sa.contains(q) -> listOf("SA")
            ae.contains(q) -> listOf("AE"); kw.contains(q) -> listOf("KW")
            qa.contains(q) -> listOf("QA"); bh.contains(q) -> listOf("BH")
            om.contains(q) -> listOf("OM"); jo.contains(q) -> listOf("JO")
            lb.contains(q) -> listOf("LB"); sy.contains(q) -> listOf("SY")
            iq.contains(q) -> listOf("IQ"); ps.contains(q) -> listOf("PS")
            ye.contains(q) -> listOf("YE"); sd.contains(q) -> listOf("SD")
            ly.contains(q) -> listOf("LY"); tn.contains(q) -> listOf("TN")
            dz.contains(q) -> listOf("DZ"); ma.contains(q) -> listOf("MA")
            mr.contains(q) -> listOf("MR"); fr.contains(q) -> listOf("FR")
            uk.contains(q) -> listOf("UK", "GB"); de.contains(q) -> listOf("DE")
            us.contains(q) -> listOf("US"); else -> emptyList()
        }
    }

    private fun getCategoryForQuery(q: String): String? = when {
        listOf("اخبار", "news").contains(q) -> "NEWS"
        listOf("قران", "quran").contains(q) -> "QURAN"
        listOf("رياضه", "sports", "sport").contains(q) -> "SPORTS"
        listOf("موسيقي", "music", "اغاني").contains(q) -> "MUSIC"
        listOf("توك شو", "talk", "حوار").contains(q) -> "TALK"
        else -> null
    }

    private fun updateCategoryCounts(list: List<StationEntity>) {
        categoryCounts.value = CategoryCounts(
            allCount = list.size, favCount = list.count { it.isFavorite },
            egyptCount = list.count { it.countryCode.equals("EG", true) || it.category.equals("EGYPT", true) },
            quranCount = list.count { it.category.equals("QURAN", true) },
            newsCount = list.count { it.category.equals("NEWS", true) },
            musicCount = list.count { it.category.equals("MUSIC", true) },
            sportsCount = list.count { it.category.equals("SPORTS", true) },
            talkCount = list.count { it.category.equals("TALK", true) }
        )
    }

    fun setCurrentStation(station: StationEntity?) { _currentPlayingStation.value = station }

    fun toggleFavorite(station: StationEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(station.uuid, station.isFavorite)
            _currentPlayingStation.value = _currentPlayingStation.value?.let {
                if (it.uuid == station.uuid) it.copy(isFavorite = !station.isFavorite) else it
            }
        }
    }

    fun refreshData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _isLoading.value = true
                _refreshResult.emit(repository.forceRefreshAll())
                _isLoading.value = false
            } catch (e: Exception) {
                _isLoading.value = false
            }
        }
    }

    fun stopHealthCheck() {
        repository.stopHealthCheck()
    }
}