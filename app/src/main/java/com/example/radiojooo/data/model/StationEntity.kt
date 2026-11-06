package com.example.radiojooo.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stations")
data class StationEntity(
    @PrimaryKey val uuid: String,
    val name: String,
    val primaryUrl: String,
    val fallbackUrl: String = "",
    val category: String = "MUSIC",
    val countryCode: String = "",
    val bitrate: Int = 64,
    val isFavorite: Boolean = false,
    val popularity: Int = 0,
    val lastUpdated: Long = 0L,
    val lastUrlWorks: Boolean? = null,
    val failCount: Int = 0,
    val lastCheckedAt: Long = 0L
)