package com.example.radiojooo.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.example.radiojooo.data.local.AppDatabase
import com.example.radiojooo.data.model.StationEntity
import com.example.radiojooo.data.repository.RadioRepository
import kotlinx.coroutines.launch

class RadioViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: RadioRepository

    val stations: LiveData<List<StationEntity>>

    init {
        val stationDao = AppDatabase.getDatabase(application).stationDao()
        repository = RadioRepository(stationDao)
        stations = repository.allStations.asLiveData()

        viewModelScope.launch {
            repository.checkAndSeedInitialStations()
        }
    }

    fun toggleFavorite(station: StationEntity) {
        viewModelScope.launch {
            repository.toggleFavorite(station)
        }
    }
}
