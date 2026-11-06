package com.example.radiojooo

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.radiojooo.data.worker.StationSyncWorker
import com.example.radiojooo.data.worker.StationUrlHealerWorker
import java.util.concurrent.TimeUnit

class RadioApp : Application() {

    override fun onCreate() {
        super.onCreate()
        scheduleWorkers()
    }

    private fun scheduleWorkers() {
        // 1. Health Check (every 6 hours) - Very lightweight (HEAD requests only)
        // Runs on any network (Wi-Fi or Mobile Data) because it barely consumes data.
        val syncConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<StationSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(syncConstraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "station_sync_worker",
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest
        )

        // 2. URL Healer (every 7 days) - Heavy operations (Web Scraping)
        // STRICTLY Wi-Fi ONLY (UNMETERED) to prevent any mobile data consumption.
        val healConstraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED) // Wi-Fi Only
            .build()

        val healRequest = PeriodicWorkRequestBuilder<StationUrlHealerWorker>(7, TimeUnit.DAYS)
            .setConstraints(healConstraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "station_url_healer_worker",
            ExistingPeriodicWorkPolicy.UPDATE,
            healRequest
        )
    }
}