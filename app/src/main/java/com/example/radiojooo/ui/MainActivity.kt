package com.example.radiojooo.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.example.radiojooo.data.StationEntity
import com.example.radiojooo.databinding.ActivityMainBinding
import com.example.radiojooo.player.RadioService
import com.example.radiojooo.viewmodel.MainViewModel

/**
 * الشاشة الرئيسية للتطبيق.
 *
 * التغييرات عن النسخة القديمة:
 * - حُذف نمط bindService / ServiceConnection لأنه لا يعمل مع MediaSessionService.
 * - التحكم بالخدمة يتم عبر RadioService.getInstance() (نمط Singleton).
 * - بدء المحطة يتم عبر RadioService.requestPlayStation(context, ...).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* النتيجة غير ضرورية */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun playStation(station: StationEntity) {
        viewModel.setCurrentStation(station)

        RadioService.requestPlayStation(
            this,
            title = station.name,
            uuid = station.uuid,
            pUrl = station.primaryUrl,
            fUrl = station.fallbackUrl
        )

        updateMiniPlayer()
    }

    private fun updateMiniPlayer() {
        // تحديث عناصر الواجهة المصغرة (عنوان المحطة، حالة التشغيل...)
        // يُستدعى بعد اختيار محطة أو عند تغيّر حالة التشغيل.
    }

    override fun onResume() {
        super.onResume()
        RadioService.getInstance()?.setAppForegroundState(true)
        updateMiniPlayer()
    }

    override fun onPause() {
        super.onPause()
        RadioService.getInstance()?.setAppForegroundState(false)
    }
}
