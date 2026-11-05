package com.example.radiojooo.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.radiojooo.data.repository.RadioRepository

class StationSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val repository = RadioRepository(applicationContext)
            // بنجلب المحطات الجديدة بس، من غير أي فحص بيخفي المحطات
            repository.fetchStationsFromServer()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}