package com.example.radiojooo.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RadioActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_PLAY_PAUSE -> {
                RadioService.getInstance()?.let { service ->
                    if (service.isPlaying()) {
                        service.pausePlayback()
                    } else {
                        service.resumePlayback()
                    }
                    // ✅ MediaSessionService بتحدث الإشعار تلقائياً
                    // مش محتاجين نستدعي updateNotification()
                }
            }
            ACTION_STOP -> {
                RadioService.getInstance()?.closeApp()
            }
        }
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.example.radiojooo.ACTION_PLAY_PAUSE"
        const val ACTION_STOP = "com.example.radiojooo.ACTION_STOP"
    }
}
