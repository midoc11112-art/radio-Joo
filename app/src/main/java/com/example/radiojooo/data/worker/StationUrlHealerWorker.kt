package com.example.radiojooo.data.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.radiojooo.data.repository.RadioRepository

class StationUrlHealerWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val repo = RadioRepository(applicationContext)
            val (healed, failed) = repo.healBatch(30)
            Log.d("HealerWorker", "Batch done: healed=$healed, failed=$failed")
            Result.success()
        } catch (e: Exception) {
            Log.e("HealerWorker", "Error: ${e.message}")
            Result.retry()
        }
    }
}