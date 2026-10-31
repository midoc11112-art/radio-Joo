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
            val healed = repo.validateAndHealStations()
            android.util.Log.d("RadioHealthWorker", "تم فحص المحطات وإصلاح $healed رابط.")
            Result.success()
        } catch (e: Exception) {
            android.util.Log.e("RadioHealthWorker", "فشل فحص الصحة", e)
            Result.retry()
        }
    }
}
