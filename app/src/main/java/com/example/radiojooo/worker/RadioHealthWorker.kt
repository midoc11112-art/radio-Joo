package com.example.radiojooo.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.radiojooo.data.local.AppDatabase
import com.example.radiojooo.data.repository.RadioRepository

class RadioHealthWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            val dao = AppDatabase.getDatabase(applicationContext).stationDao()
            val repo = RadioRepository(dao, applicationContext.cacheDir)
            
            // 1. تحديث المحطات من المصادر الخمسة (يضيف الجديد ويحدث القديم)
            repo.refreshStations()
            
            // 2. فحص الروابط القديمة وإصلاح الميت منها
            val healed = repo.validateAndHealStations()
            
            android.util.Log.d("RadioHealthWorker", "تم التحديث التلقائي وإصلاح $healed رابط.")
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("RadioHealthWorker", "فشل التحديث الدوري", e)
            Result.retry()
        }
    }
}
