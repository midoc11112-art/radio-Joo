package com.example.radiojooo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.radiojooo.data.model.StationEntity
import com.example.radiojooo.data.repository.RadioRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RadioViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RadioRepository(application)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedCategory = MutableStateFlow("ALL")
    val selectedCategory: StateFlow<String> = _selectedCategory.asStateFlow()

    private val _currentPlayingStation = MutableStateFlow<StationEntity?>(null)
    val currentPlayingStation: StateFlow<StationEntity?> = _currentPlayingStation.asStateFlow()

    private val _refreshResult = MutableSharedFlow<Boolean>()
    val refreshResult: SharedFlow<Boolean> = _refreshResult.asSharedFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val allStations: StateFlow<List<StationEntity>> = repository.allStations
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val stations: StateFlow<List<StationEntity>> = combine(
        repository.allStations,
        _searchQuery,
        _selectedCategory
    ) { stationsList, query, category ->
        stationsList.filter { station ->
            val matchesQuery = query.isEmpty() ||
                    station.name.contains(query, ignoreCase = true) ||
                    station.category.contains(query, ignoreCase = true) ||
                    station.countryCode.contains(query, ignoreCase = true)

            val matchesCategory = when (category.uppercase()) {
                "ALL" -> true
                "FAVORITES" -> station.isFavorite
                else -> station.category.equals(category, ignoreCase = true)
            }

            matchesQuery && matchesCategory
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val favoriteStations: StateFlow<List<StationEntity>> = repository.favoriteStations
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val categoryCounts: StateFlow> = repository.allStations
        .map { list ->
            val counts = mutableMapOf<String, Int>()
            counts["ALL"] = list.size
            counts["FAVORITES"] = list.count { it.isFavorite }
            list.forEach { station ->
                val cat = station.category.uppercase()
                counts[cat] = (counts[cat] ?: 0) + 1
            }
            counts
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    init {
        viewModelScope.launch {
            repository.ensureInitialDataLoaded()
            refreshData()
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setCategory(category: String) {
        _selectedCategory.value = category
    }

    fun setCurrentStation(station: StationEntity?) {
        _currentPlayingStation.value = station
    }

    fun toggleFavorite(station: StationEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(station.uuid)
        }
    }

    fun toggleFavorite(uuid: String) {
        viewModelScope.launch {
            repository.toggleFavorite(uuid)
        }
    }

    fun refreshData() {
        viewModelScope.launch {
            _isRefreshing.value = true
            val success = repository.fetchStationsFromServer()
            _refreshResult.emit(success)
            _isRefreshing.value = false
        }
    }

    fun refreshStations() {
        refreshData()
    }
}
