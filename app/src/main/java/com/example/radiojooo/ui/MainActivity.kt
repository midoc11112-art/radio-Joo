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
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import com.example.radiojooo.data.model.StationEntity
import com.example.radiojooo.databinding.ActivityMainBinding
import com.example.radiojooo.player.RadioService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: RadioViewModel by viewModels()
    private lateinit var stationAdapter: StationAdapter

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

        setupRecyclerView()
        observeViewModel()
        checkAndRequestNotificationPermission()
    }

    private fun setupRecyclerView() {
        stationAdapter = StationAdapter(
            onItemClick = { station ->
                onStationClicked(station)
            },
            onFavoriteClick = { station ->
                viewModel.toggleFavorite(station)
            }
        )
        binding.rvStations.adapter = stationAdapter
    }

    private fun observeViewModel() {
        viewModel.stations.observe(this, Observer { stations ->
            stationAdapter.submitList(stations)
        })
    }

    private fun onStationClicked(station: StationEntity) {
        val intent = Intent(this, RadioService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        binding.root.postDelayed({
            RadioService.getInstance()?.playStation(
                url = station.streamUrl,
                title = station.name,
                stationSubtitle = station.category
            )
        }, 200)

        val playerIntent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("EXTRA_STREAM_URL", station.streamUrl)
            putExtra("EXTRA_STATION_TITLE", station.name)
            putExtra("EXTRA_STATION_SUBTITLE", station.category)
        }
        startActivity(playerIntent)
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
