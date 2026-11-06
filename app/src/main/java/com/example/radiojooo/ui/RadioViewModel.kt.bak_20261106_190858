package com.example.radiojooo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.radiojooo.data.local.AppDatabase
import com.example.radiojooo.data.model.StationEntity
import com.example.radiojooo.data.repository.HealthCheckResult
import com.example.radiojooo.data.repository.RadioRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

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

    init {
        val stationDao = AppDatabase.getDatabase(application).stationDao()
        repository = RadioRepository(application, stationDao, application.cacheDir)
        val rawFlow = MutableStateFlow<List<StationEntity>>(emptyList())
        allRawStations = rawFlow.asStateFlow()

        viewModelScope.launch { repository.allStations.collect { list -> rawFlow.value = list; updateCategoryCounts(list) } }
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
                    val q = query.trim().lowercase()
                    val isGreater = q.contains("اكبر") || q.contains("أكبر") || q.contains(">")
                    val isLess = q.contains("اصغر") || q.contains("أصغر") || q.contains("<")
                    if (isGreater) {
                        val numStr = q.replace("اكبر", "").replace("أكبر", "").replace(">", "").trim()
                        val bitrateNum = numStr.toIntOrNull()
                        if (bitrateNum != null) filtered = filtered.filter { it.bitrate > bitrateNum && it.bitrate > 0 } else filtered = emptyList()
                    } else if (isLess) {
                        val numStr = q.replace("اصغر", "").replace("أصغر", "").replace("<", "").trim()
                        val bitrateNum = numStr.toIntOrNull()
                        if (bitrateNum != null) filtered = filtered.filter { it.bitrate in 1 until bitrateNum } else filtered = emptyList()
                    } else {
                        val bitrateNum = q.toIntOrNull()
                        val countryCodes = getCountryCodesForQuery(q)
                        val matchedCategory = getCategoryForQuery(q)
                        filtered = filtered.filter {
                            (bitrateNum != null && it.bitrate == bitrateNum) ||
                            it.name.lowercase().contains(q) ||
                            levenshteinDistance(q, it.name.lowercase()) <= 2 ||
                            it.category.lowercase().contains(q) ||
                            it.countryCode.lowercase().contains(q) ||
                            (matchedCategory != null && it.category.equals(matchedCategory, ignoreCase = true)) ||
                            (countryCodes.isNotEmpty() && countryCodes.contains(it.countryCode.uppercase()))
                        }
                    }
                }
                filtered.sortedByDescending { it.popularity }
            }.collect { filteredFlow.value = it }
        }
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
        val eg = listOf("مصر", "egypt", "masr", "eg"); val sa = listOf("السعودية", "saudi", "ksa", "sa"); val ae = listOf("الامارات", "الإمارات", "uae", "emirates", "ae"); val kw = listOf("الكويت", "kuwait", "kw"); val qa = listOf("قطر", "qatar", "qa"); val bh = listOf("البحرين", "bahrain", "bh"); val om = listOf("عمان", "oman", "om"); val jo = listOf("الاردن", "الأردن", "jordan", "jo"); val lb = listOf("لبنان", "lebanon", "lb"); val sy = listOf("سوريا", "syria", "sy"); val iq = listOf("العراق", "iraq", "iq"); val ps = listOf("فلسطين", "palestine", "ps"); val ye = listOf("اليمن", "yemen", "ye"); val sd = listOf("السودان", "sudan", "sd"); val ly = listOf("ليبيا", "libya", "ly"); val tn = listOf("تونس", "tunisia", "tn"); val dz = listOf("الجزائر", "algeria", "dz"); val ma = listOf("المغرب", "morocco", "ma"); val mr = listOf("موريتانيا", "mauritania", "mr"); val fr = listOf("فرنسا", "france", "fr"); val uk = listOf("بريطانيا", "uk", "gb", "england"); val de = listOf("المانيا", "ألمانيا", "germany", "de"); val us = listOf("امريكا", "أمريكا", "usa", "us")
        return when { eg.contains(q) -> listOf("EG"); sa.contains(q) -> listOf("SA"); ae.contains(q) -> listOf("AE"); kw.contains(q) -> listOf("KW"); qa.contains(q) -> listOf("QA"); bh.contains(q) -> listOf("BH"); om.contains(q) -> listOf("OM"); jo.contains(q) -> listOf("JO"); lb.contains(q) -> listOf("LB"); sy.contains(q) -> listOf("SY"); iq.contains(q) -> listOf("IQ"); ps.contains(q) -> listOf("PS"); ye.contains(q) -> listOf("YE"); sd.contains(q) -> listOf("SD"); ly.contains(q) -> listOf("LY"); tn.contains(q) -> listOf("TN"); dz.contains(q) -> listOf("DZ"); ma.contains(q) -> listOf("MA"); mr.contains(q) -> listOf("MR"); fr.contains(q) -> listOf("FR"); uk.contains(q) -> listOf("UK", "GB"); de.contains(q) -> listOf("DE"); us.contains(q) -> listOf("US"); else -> emptyList() }
    }

    private fun getCategoryForQuery(q: String): String? = when { listOf("أخبار", "اخبار", "news").contains(q) -> "NEWS"; listOf("قرآن", "قران", "quran").contains(q) -> "QURAN"; listOf("رياضة", "رياضه", "sports", "sport").contains(q) -> "SPORTS"; listOf("موسيقى", "موسيقي", "music", "أغاني", "اغاني").contains(q) -> "MUSIC"; listOf("توك شو", "talk", "حوار").contains(q) -> "TALK"; else -> null }

    private fun updateCategoryCounts(list: List<StationEntity>) {
        categoryCounts.value = CategoryCounts(
            allCount = list.size, favCount = list.count { it.isFavorite },
            egyptCount = list.count { it.countryCode.equals("EG", true) || it.category.equals("EGYPT", true) },
            quranCount = list.count { it.category.equals("QURAN", true) }, newsCount = list.count { it.category.equals("NEWS", true) },
            musicCount = list.count { it.category.equals("MUSIC", true) }, sportsCount = list.count { it.category.equals("SPORTS", true) },
            talkCount = list.count { it.category.equals("TALK", true) }
        )
    }

    fun setCurrentStation(station: StationEntity?) { _currentPlayingStation.value = station }
    fun toggleFavorite(station: StationEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(station.uuid, station.isFavorite)
            _currentPlayingStation.value = _currentPlayingStation.value?.let { if (it.uuid == station.uuid) it.copy(isFavorite = !station.isFavorite) else it }
        }
    }

    fun refreshData() {
        viewModelScope.launch(Dispatchers.IO) {
            try { _isLoading.value = true; _refreshResult.emit(repository.forceRefreshAll()); _isLoading.value = false } catch (e: Exception) { _isLoading.value = false }
        }
    }

    fun stopHealthCheck() {
        repository.stopHealthCheck()
    }
}