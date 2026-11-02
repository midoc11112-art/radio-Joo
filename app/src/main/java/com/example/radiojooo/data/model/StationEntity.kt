package com.example.radiojooo.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stations")
data class StationEntity(
    @PrimaryKey
    val uuid: String,
    val name: String,
    val primaryUrl: String,
    val fallbackUrls: String = "",
    val category: String = "عام",
    val favicon: String = "",
    val isFavorite: Boolean = false,
    val popularityScore: Int = 0,
    val actualBitrate: Int = 0,
    val isOnline: Boolean = true
)
