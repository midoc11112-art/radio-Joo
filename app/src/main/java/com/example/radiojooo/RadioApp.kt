package com.example.radiojooo

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.radiojooo.data.worker.StationSyncWorker
import java.util.concurrent.TimeUnit

class RadioApp : Application() {

    override fun onCreate() {
        super.onCreate()
        scheduleStationSync()
    }

    private fun scheduleStationSync() {
        val syncRequest = PeriodicWorkRequestBuilder<StationSyncWorker>(
            24, TimeUnit.HOURS
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "station_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }
}
