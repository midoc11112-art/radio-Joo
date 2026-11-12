package com.example.radiojooo.player

import com.example.radiojooo.data.Server
import com.example.radiojooo.data.Station
import kotlinx.coroutines.flow.MutableStateFlow

enum class Status { IDLE, CONNECTING, PLAYING, PAUSED, SWITCHING, HEALING, ERROR }

/** Everything the UI needs to draw the mini player and the full player. Published by [RadioService] once per second. */
data class PlaybackUi(
    val station: Station? = null,
    val status: Status = Status.IDLE,
    val serverIndex: Int = 0,          // 1-based index of the server being tried
    val serverCount: Int = 0,
    val kbps: Int = 0,                 // declared / measured bitrate of the current server
    val speedKBps: Float = 0f,         // live download speed
    val seconds: Long = 0,             // listening time
    val bytes: Long = 0,               // data used in this session
    val sleepRemainingSec: Long = 0,
    val servers: List<Server> = emptyList(),
    val currentIndex: Int = -1,        // 0-based index inside [servers]
    val recording: Boolean = false,
    val recSeconds: Long = 0,
    val recBytes: Long = 0,
    val canRecord: Boolean = false
)

object PlaybackBus {
    val state = MutableStateFlow(PlaybackUi())
}
