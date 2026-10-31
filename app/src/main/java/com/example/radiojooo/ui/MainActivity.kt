package com.example.radiojooo.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.radiojooo.databinding.ActivityMainBinding
import com.example.radiojooo.worker.RadioHealthWorker
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: RadioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAllPermissions()
        scheduleStationSync()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ إخفاء مؤشر التحميل تماماً عند بداية فتح التطبيق
        binding.swipeRefreshLayout.isRefreshing = false

        setupRecyclerView()
        setupSearch()
        setupChipGroup()
        setupSwipeRefresh()
        setupHeaderButtons()
        setupMiniPlayer()
        observeViewModel()
    }

    private fun requestAllPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun scheduleStationSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val healthRequest = PeriodicWorkRequestBuilder<RadioHealthWorker>(12, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "RadioHealthCheck",
            ExistingPeriodicWorkPolicy.KEEP,
            healthRequest
        )
    }

    private fun setupRecyclerView() {
        // TODO: قم بإضافة كود الـ RecyclerView الخاص بك هنا
    }

    private fun setupSearch() {
        // TODO: قم بإضافة كود البحث هنا
    }

    private fun setupChipGroup() {
        // TODO: قم بإضافة كود الفلاتر (ChipGroup) هنا
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            viewModel.refreshData()
        }
    }

    private fun setupHeaderButtons() {
        // TODO: أزرار الهيدر
    }

    private fun setupMiniPlayer() {
        // TODO: المشغل المصغر
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { isLoading ->
            binding.swipeRefreshLayout.isRefreshing = isLoading
        }
    }
}
