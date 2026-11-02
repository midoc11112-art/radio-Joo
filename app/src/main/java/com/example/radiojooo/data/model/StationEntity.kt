package com.example.radiojooo.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stations")
data class StationEntity(
    @PrimaryKey
    val uuid: String,
    val name: String,
    val primaryUrl: String,
    val fallbackUrl: String,
    val category: String,
    val countryCode: String,
    val bitrate: Int,
    var isFavorite: Boolean = false,
    val popularity: Int = 0,
    val lastUpdated: Long = System.currentTimeMillis()
)
