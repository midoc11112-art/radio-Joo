package com.example.radiojooo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.radiojooo.data.local.AppDatabase
import com.example.radiojooo.data.model.StationEntity
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
    val isLoading: StateFlow<Boolean> = _isLoading.asState
