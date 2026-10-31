package com.example.radiojooo

import android.app.Application
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.radiojooo.worker.RadioHealthWorker
import java.util.concurrent.TimeUnit

class RadioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        scheduleStationSync()
    }

    private fun scheduleStationSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val healthRequest = PeriodicWorkRequestBuilder<RadioHealthWorker>(
            12, TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "RadioHealthCheck",
            ExistingPeriodicWorkPolicy.KEEP,
            healthRequest
        )
    }
}
