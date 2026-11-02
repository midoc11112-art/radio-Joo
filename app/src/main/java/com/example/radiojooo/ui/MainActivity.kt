package com.example.radiojooo.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import com.example.radiojooo.databinding.ActivityMainBinding
import com.example.radiojooo.player.RadioService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // مراقب حالة ExoPlayer لتحديث الأزرار في الواجهة فوراً
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseButtonUI(isPlaying)
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            checkAndRequestBatteryOptimization()
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (!shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    showNotificationSettingsDialog()
                } else {
                    Toast.makeText(
                        this,
                        "إذن الإشعارات مطلوب للتحكم بالراديو من شريط الإشعارات وشاشة القفل",
                        Toast.LENGTH_LONG
                    ).show()
                    checkAndRequestBatteryOptimization()
                }
            } else {
                checkAndRequestBatteryOptimization()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUIControls()
        checkAndRequestNotificationPermission()
    }

    override fun onStart() {
        super.onStart()
        attachPlayerListener()
    }

    override fun onStop() {
        super.onStop()
        detachPlayerListener()
    }

    private fun setupUIControls() {
        // ربط زر التشغيل/الإيقاف المؤقت
        binding.btnPlayPause.setOnClickListener {
            val service = RadioService.getInstance()
            if (service != null) {
                if (service.isPlaying()) {
                    service.pause()
                } else {
                    service.resume()
                }
            } else {
                // تشغيل المحطة لأول مرة وبدء الخدمة كـ Foreground Service
                startRadioStream("https://stream.radiojoo.com/live", "إذاعة راديو جو", "بث مباشر")
            }
        }

        // ربط زر الإيقاف التام
        binding.btnStop.setOnClickListener {
            RadioService.getInstance()?.stop()
            updatePlayPauseButtonUI(false)
        }
    }

    private fun startRadioStream(url: String, title: String, subtitle: String) {
        val intent = Intent(this, RadioService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // انتظار تهيئة الخدمة ثم بدء البث المباشر
        binding.root.postDelayed({
            RadioService.getInstance()?.playStation(url, title, subtitle)
            attachPlayerListener()
        }, 200)
    }

    private fun attachPlayerListener() {
        val service = RadioService.getInstance()
        if (service != null) {
            val player = service.getPlayer()
            player?.addListener(playerListener)
            updatePlayPauseButtonUI(service.isPlaying())
        } else {
            updatePlayPauseButtonUI(false)
        }
    }

    private fun detachPlayerListener() {
        RadioService.getInstance()?.getPlayer()?.removeListener(playerListener)
    }

    private fun updatePlayPauseButtonUI(isPlaying: Boolean) {
        if (isPlaying) {
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        } else {
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private fun checkAndRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED -> {
                    checkAndRequestBatteryOptimization()
                }
                else -> {
                    requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        } else {
            checkAndRequestBatteryOptimization()
        }
    }

    private fun checkAndRequestBatteryOptimization() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
            showBatteryOptimizationDialog()
        }
    }

    private fun showBatteryOptimizationDialog() {
        AlertDialog.Builder(this)
            .setTitle("تعطيل تحسين البطارية")
            .setMessage("لضمان استمرار تشغيل الراديو في الخلفية وعند إطفاء الشاشة دون انقطاع على هواتف سامسونج، يلزم استثناء التطبيق من قيود تحسين البطارية.")
            .setPositiveButton("استثناء التطبيق") { _, _ ->
                requestIgnoreBatteryOptimization()
            }
            .setNegativeButton("إلغاء", null)
            .setCancelable(false)
            .show()
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        }
    }

    private fun showNotificationSettingsDialog() {
        AlertDialog.Builder(this)
            .setTitle("إذن الإشعارات مطلوب")
            .setMessage("لقد قمت برفض إذن الإشعارات بشكل دائم. يلزم تفعيل الإذن يدوياً من إعدادات التطبيق لتتمكن من التحكم بالراديو عبر شريط الإشعارات وشاشة القفل.")
            .setPositiveButton("الانتقال للإعدادات") { _, _ ->
                openAppSettings()
            }
            .setNegativeButton("إلغاء") { dialog, _ ->
                dialog.dismiss()
                checkAndRequestBatteryOptimization()
            }
            .setCancelable(false)
            .show()
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }
}
