package com.example.radiojooo.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stations")
data class StationEntity(
    @PrimaryKey val uuid: String,
    val name: String,
    val primaryUrl: String,
    val fallbackUrl: String = "",
    val tags: String = "",
    val country: String = "",
    val category: String = "GENERAL",
    val countryCode: String = "EG",
    val bitrate: Int = 32,
    val isFavorite: Boolean = false,
    val popularityScore: Int = 0,
    // ✅ الحقل الجديد: البتريت الفعلي المقاس (0 يعني لم يتم قياسه بعد)
    val actualBitrate: Int = 0 
)
