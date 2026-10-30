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
            val db = AppDatabase.getDatabase(applicationContext)
            val repository = RadioRepository(db.stationDao())
            
            // ✅ تحديث المحطات من Radio Browser في الخلفية
            repository.refreshStations()
            
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}
