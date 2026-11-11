package com.example.radiojooo.data

/** One stream server of a station. [kbps] = 0 means unknown. [hls] = needs the cloud audio-only relay. */
data class Server(val url: String, val kbps: Int, val hls: Boolean)

data class Station(
    val id: String,
    val name: String,
    val category: String,      // QURAN | EGYPT | NEWS | MUSIC | SPORTS | TALK
    val countryCode: String,
    val popularity: Int,
    val servers: List<Server>,
    val isFavorite: Boolean = false
) {
    /** Bitrate of the cheapest server, shown on the card. */
    val bitrate: Int get() = servers.firstOrNull()?.kbps ?: 0
}

/** One learning event about a server, reported to the cloud so every phone benefits. */
data class Report(val url: String, val ok: Boolean, val startupMs: Long, val kbps: Int)
