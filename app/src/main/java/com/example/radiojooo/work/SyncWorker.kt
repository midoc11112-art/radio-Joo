package com.example.radiojooo.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.radiojooo.Graph
import com.example.radiojooo.data.Api
import java.util.concurrent.TimeUnit

/** Periodic refresh of the catalog + upload of what this phone learned (tiny JSON). */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Graph.repo(applicationContext).sync()
        val health = Graph.health(applicationContext)
        val reports = health.drainReports()
        if (reports.isNotEmpty() && !Api.report(reports)) health.requeue(reports)
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("catalog_sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
