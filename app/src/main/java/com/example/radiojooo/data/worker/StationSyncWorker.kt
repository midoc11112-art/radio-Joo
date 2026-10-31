package com.example.radiojooo.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.radiojooo.data.local.AppDatabase
import com.example.radiojooo.data.repository.RadioRepository

class StationSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val dao = AppDatabase.getDatabase(applicationContext).stationDao()
            val repo = RadioRepository(dao, applicationContext.cacheDir)
            
            // تحديث وجلب محطات جديدة
            repo.refreshStations()
            
            // فحص الروابط الميتة وإصلاحها
            repo.validateAndHealStations()
            
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
