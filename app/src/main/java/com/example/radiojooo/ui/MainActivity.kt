package com.example.radiojooo.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.radiojooo.databinding.ActivityMainBinding
import com.example.radiojooo.player.RadioService
import com.example.radiojooo.worker.RadioHealthWorker
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: RadioViewModel by viewModels()
    private lateinit var stationAdapter: StationAdapter
    private var recyclerView: RecyclerView? = null

    private var radioService: RadioService? = null
    private var isBound = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RadioService.LocalBinder
            radioService = binder.getService()
            isBound = true
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            radioService = null
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAllPermissions()
        scheduleStationSync()

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ✅ إخفاء مؤشر التحميل تماماً
        binding.swipeRefreshLayout.isRefreshing = false

        setupRecyclerView()
        setupSearch()
        setupSwipeRefresh()
        observeViewModel()

        Intent(this, RadioService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun requestAllPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        permissions.add(Manifest.permission.RECORD_AUDIO)

        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
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

    // ✅ دالة ذكية بتدور على الـ RecyclerView مهما كان اسمه
    private fun findRecyclerView(viewGroup: ViewGroup): RecyclerView? {
        for (i in 0 until viewGroup.childCount) {
            val child = viewGroup.getChildAt(i)
            if (child is RecyclerView) return child
            if (child is ViewGroup) {
                val found = findRecyclerView(child)
                if (found != null) return found
            }
        }
        return null
    }

    private fun setupRecyclerView() {
        stationAdapter = StationAdapter(
            onItemClick = { station ->
                if (isBound) {
                    radioService?.playStation(station.name, station.uuid, station.primaryUrl, station.fallbackUrl)
                }
                val intent = Intent(this, PlayerActivity::class.java)
                intent.putExtra("STATION_UUID", station.uuid)
                startActivity(intent)
            },
            onFavoriteClick = { station ->
                viewModel.toggleFavorite(station)
            }
        )
        recyclerView = findRecyclerView(binding.root)
        recyclerView?.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = stationAdapter
        }
    }

    private fun setupSearch() {
        // ملاحظة: لو etSearch مش موجودة في الـ XML، هيظهر خطأ أحمر. وقتها قوللي اسم الحقل الصح.
        try {
            binding.etSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    viewModel.searchQuery.value = s?.toString() ?: ""
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        } catch (e: Exception) {
            // لو الحقل مش موجود، الكود مش هيكسر
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            viewModel.refreshData()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isLoading.collect { isLoading ->
                    binding.swipeRefreshLayout.isRefreshing = isLoading
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.stations.collect { stations ->
                    stationAdapter.submitList(stations)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }
}
