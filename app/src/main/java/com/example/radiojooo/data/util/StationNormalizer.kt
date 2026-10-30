package com.example.radiojooo.data.util

import com.example.radiojooo.data.model.StationEntity

object StationNormalizer {

    private val FAMOUS_STATION_CATEGORIES = mapOf(
        "eg_nogoum_fm" to "MUSIC",
        "eg_quran_cairo" to "QURAN",
        "eg_radio_masr" to "NEWS",
        "eg_mega_fm" to "MUSIC",
        "eg_nagham_fm" to "MUSIC",
        "eg_mix_fm" to "MUSIC",
        "eg_aljazeera" to "NEWS"
    )

    fun classify(station: StationEntity): String {
        return classify(uuid = station.uuid, name = station.name, tags = station.tags)
    }

    fun classify(uuid: String, name: String, tags: String = ""): String {
        val cleanUuid = uuid.lowercase().trim()
        val cleanTags = tags.lowercase().trim()
        val cleanName = name.lowercase().trim()

        FAMOUS_STATION_CATEGORIES[cleanUuid]?.let { return it }

        if (cleanTags.contains("quran") || cleanTags.contains("islamic") || cleanTags.contains("quranic")) return "QURAN"
        if (cleanTags.contains("news") || cleanTags.contains("talk") || cleanTags.contains("information")) return "NEWS"
        if (cleanTags.contains("sport") || cleanTags.contains("sports")) return "SPORTS"
        if (cleanTags.contains("music") || cleanTags.contains("pop") || cleanTags.contains("rock") || cleanTags.contains("hits")) return "MUSIC"

        if (cleanName.contains("القرآن") || cleanName.contains("قرآن") || cleanName.contains("quran")) return "QURAN"
        if (cleanName.contains("أخبار") || cleanName.contains("اخبار") || cleanName.contains("news") || cleanName.contains("جزيرة") || cleanName.contains("حدث")) return "NEWS"
        if (cleanName.contains("رياضة") || cleanName.contains("sport")) return "SPORTS"
        if (cleanName.contains("نجوم") || cleanName.contains("موسيقى") || cleanName.contains("نغم") || cleanName.contains("ميجا") || cleanName.contains("fm") || cleanName.contains("music")) return "MUSIC"

        return "GENERAL"
    }
}
