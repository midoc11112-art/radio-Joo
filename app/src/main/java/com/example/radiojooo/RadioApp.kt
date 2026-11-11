package com.example.radiojooo

import android.app.Application
import com.example.radiojooo.work.SyncWorker

class RadioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncWorker.schedule(this)   // self-updating catalog: every 6 hours in the background, only with a network
    }
}
