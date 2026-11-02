package com.example.radiojooo.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RadioActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = RadioService.getInstance() ?: return

        when (intent.action) {
            ACTION_PLAY_PAUSE -> {
                if (service.isPlaying()) {
                    service.pausePlayback()
                } else {
                    service.resumePlayback()
                }
                service.updateNotification()
            }
            ACTION_STOP -> {
                service.closeApp()
            }
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.example.radiojooo.ACTION_PLAY_PAUSE"
        const val ACTION_STOP = "com.example.radiojooo.ACTION_STOP"
    }
}
