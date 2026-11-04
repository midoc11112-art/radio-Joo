package com.example.radiojooo.data.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.radiojooo.data.local.AppDatabase
import com.example.radiojooo.data.repository.RadioRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StationSyncWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val database = AppDatabase.getDatabase(applicationContext)
            val stationDao = database.stationDao()
            val repository = RadioRepository(stationDao, applicationContext.cacheDir)

            val updatedCount = repository.fetchStationsFromServer()

            // تنظيف المحطات الميتة: اللي فشلت 3 مرات متتالية في التشغيل الحقيقي
            // (المحطات المفضلة محمية من الحذف)
            try {
                stationDao.pruneDeadStations(maxFails = 3)
            } catch (_: Exception) {}

            if (updatedCount >= 0) {
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
