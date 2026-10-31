package com.example.radiojooo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.radiojooo.data.local.AppDatabase
import com.example.radiojooo.data.model.StationEntity
import com.example.radiojooo.data.repository.RadioRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class CategoryCounts(
    val allCount: Int = 0,
    val favCount: Int = 0,
    val egyptCount: Int = 0,
    val quranCount: Int = 0,
    val newsCount: Int = 0,
    val musicCount: Int = 0,
    val sportsCount: Int = 0
)

class RadioViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: RadioRepository

    val allRawStations: StateFlow<List<StationEntity>>
    val searchQuery = MutableStateFlow("")
    val selectedCategory = MutableStateFlow("ALL")

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
        repository = RadioRepository(stationDao, application.cacheDir)

        val rawFlow = MutableStateFlow<List<StationEntity>>(emptyList())
        allRawStations = rawFlow.asStateFlow()

        viewModelScope.launch {
            repository.ensureInitialDataLoaded()
            repository.allStations.collect { list ->
                rawFlow.value = list
                updateCategoryCounts(list)
            }
        }

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
                    filtered = filtered.filter { 
                        it.name.lowercase().contains(q) || it.category.lowercase().contains(q) || it.countryCode.lowercase().contains(q)
                    }
                }

                // ✅ الترتيب حسب الشهرة تنازلياً
                filtered.sortedByDescending { it.popularityScore }
            }.collect {
                filteredFlow.value = it
            }
        }
    }

    private fun updateCategoryCounts(list: List<StationEntity>) {
        val all = list.size
        val fav = list.count { it.isFavorite }
        val eg = list.count { it.countryCode.equals("EG", ignoreCase = true) || it.category.equals("EGYPT", ignoreCase = true) }
        val quran = list.count { it.category.equals("QURAN", ignoreCase = true) }
        val news = list.count { it.category.equals("NEWS", ignoreCase = true) }
        val music = list.count { it.category.equals("MUSIC", ignoreCase = true) }
        val sports = list.count { it.category.equals("SPORTS", ignoreCase = true) }

        categoryCounts.value = CategoryCounts(all, fav, eg, quran, news, music, sports)
    }

    fun setCurrentStation(station: StationEntity?) {
        _currentPlayingStation.value = station
    }

    fun toggleFavorite(station: StationEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(station.uuid, station.isFavorite)
            _currentPlayingStation.value = _currentPlayingStation.value?.let {
                if (it.uuid == station.uuid) it.copy(isFavorite = !station.isFavorite) else it
            }
        }
    }

    fun refreshData() {
        viewModelScope.launch {
            _isLoading.value = true
            val count = repository.refreshStations()
            _isLoading.value = false
            _refreshResult.emit(count)
        }
    }
}
