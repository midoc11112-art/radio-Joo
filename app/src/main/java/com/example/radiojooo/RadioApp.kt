package com.example.radiojooo

import android.app.Application

class RadioApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // تم إزالة مهام WorkManager الدورية (RadioHealthWorker) 
        // لتوفير البطارية والإنترنت ولعدم الحاجة إليها
    }
}
