package com.example.radiojooo.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class RadioActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val serviceIntent = Intent(context, RadioService::class.java).apply {
            action = when (intent.action) {
                "com.example.radiojooo.ACTION_TOGGLE" -> RadioService.ACTION_TOGGLE
                "com.example.radiojooo.ACTION_EXIT" -> RadioService.ACTION_EXIT
                else -> return
            }
        }
        context.startService(serviceIntent)
    }
}