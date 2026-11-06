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
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // 1. Health Check (every 6 hours)
        val syncRequest = PeriodicWorkRequestBuilder<StationSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "station_sync_worker",
            ExistingPeriodicWorkPolicy.UPDATE,
            syncRequest
        )

        // 2. URL Healer (every 4 hours)
        val healRequest = PeriodicWorkRequestBuilder<StationUrlHealerWorker>(4, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "station_url_healer_worker",
            ExistingPeriodicWorkPolicy.UPDATE,
            healRequest
        )
    }
}