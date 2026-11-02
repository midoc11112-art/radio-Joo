package com.example.radiojooo.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RadioActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val service = RadioService.getInstance() ?: return
        when (intent?.action) {
            "ACTION_PAUSE", "com.example.radiojooo.ACTION_PAUSE" -> {
                service.pause()
            }
            "ACTION_PLAY", "com.example.radiojooo.ACTION_PLAY" -> {
                service.resume()
            }
            "ACTION_STOP", "ACTION_CLOSE", "com.example.radiojooo.ACTION_STOP" -> {
                service.stop()
            }
        }
    }
}
