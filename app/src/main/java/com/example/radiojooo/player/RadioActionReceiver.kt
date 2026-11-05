package com.example.radiojooo.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RadioActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, RadioService::class.java)
        serviceIntent.action = intent.action
        context.startService(serviceIntent)
    }

    companion object {
        const val ACTION_PLAY_PAUSE = "com.example.radiojooo.ACTION_PLAY_PAUSE"
        const val ACTION_STOP = "com.example.radiojooo.ACTION_STOP"
    }
}
