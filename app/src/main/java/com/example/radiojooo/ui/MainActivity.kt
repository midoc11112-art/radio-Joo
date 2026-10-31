package com.example.radiojooo.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.example.radiojooo.R
import com.example.radiojooo.data.model.StationEntity
import com.example.radiojooo.data.worker.StationSyncWorker
import com.example.radiojooo.databinding.ActivityMainBinding
import com.example.radiojooo.player.RadioService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: RadioViewModel by viewModels()

    private lateinit var stationAdapter: StationAdapter
    private var radioService: RadioService? = null
    private var isBound = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> }

    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateMiniPlayer()
            updateSleepTimerButton()
            updateHandler.postDelayed(this, 1000)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? RadioService.LocalBinder
            radioService = binder?.getService()
            isBound = true
            radioService?.setAppForegroundState(true)
            updateMiniPlayer()
            updateSleepTimerButton()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            radioService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAllPermissions()
        scheduleStationSync() 

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupSearch()
        setupChipGroup()
        setupSwipeRefresh()
        setupHeaderButtons()
        setupMiniPlayer()
        observeViewModel()
    }

    private fun scheduleStationSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val syncRequest = PeriodicWorkRequestBuilder<StationSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "station_sync_work",
            ExistingPeriodicWorkPolicy.KEEP,
            syncRequest
        )
    }

    private fun requestAllPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, RadioService::class.java).apply {
            action = "ACTION_LOCAL_BIND"
        }
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        radioService?.setAppForegroundState(true)
        updateHandler.post(updateRunnable)
    }

    override fun onStop() {
        super.onStop()
        radioService?.setAppForegroundState(false)
        updateHandler.removeCallbacks(updateRunnable)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun setupRecyclerView() {
        stationAdapter = StationAdapter(
            onItemClick = { station ->
                playStation(station)
            },
            onFavoriteClick = { station ->
                viewModel.toggleFavorite(station)
            }
        )
        binding.rvStations.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = stationAdapter
        }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.searchQuery.value = s?.toString() ?: ""
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun setupChipGroup() {
        binding.chipGroupCategories.setOnCheckedChangeListener { _, checkedId ->
            val category = when (checkedId) {
                R.id.chipFavorite -> "FAVORITE"
                R.id.chipEgypt -> "EGYPT"
                R.id.chipQuran -> "QURAN"
                R.id.chipNews -> "NEWS"
                R.id.chipMusic -> "MUSIC"
                R.id.chipSports -> "SPORTS"
                else -> "ALL"
            }
            viewModel.selectedCategory.value = category
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            // ✅ التعديل: إخفاء الكورة فوراً والاعتماد على التحديث في الخلفية
            binding.swipeRefreshLayout.isRefreshing = false
            viewModel.refreshData()
        }
    }

    private fun setupHeaderButtons() {
        binding.btnRefreshHeader.setOnClickListener {
            // ✅ التعديل: نفس فكرة السحب
            viewModel.refreshData()
            Toast.makeText(this, "جاري التحديث في الخلفية...", Toast.LENGTH_SHORT).show()
        }

        binding.btnSleepTimerHeader.setOnClickListener {
            showSleepTimerDialog()
        }
    }

    private fun setupMiniPlayer() {
        binding.miniPlayerHeader.setOnClickListener {
            val intent = Intent(this, PlayerActivity::class.java)
            startActivity(intent)
        }

        binding.btnMiniPlayPause.setOnClickListener {
            radioService?.let { service ->
                val intent = Intent(this, RadioService::class.java).apply {
                    action = if (service.isPlaying()) "ACTION_PAUSE" else "ACTION_PLAY"
                }
                startService(intent)
            }
        }
    }

    private fun playStation(station: StationEntity) {
        viewModel.setCurrentStation(station)

        val serviceIntent = Intent(this, RadioService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        radioService?.playStation(
            title = station.name,
            uuid = station.uuid,
            pUrl = station.primaryUrl,
            fUrl = station.fallbackUrl
        )

        updateMiniPlayer()
    }

    private fun updateSleepTimerButton() {
        val timeRemaining = radioService?.sleepTimeRemainingMs ?: 0L
        if (timeRemaining > 0) {
            val sec = (timeRemaining / 1000) % 60
            val min = (timeRemaining / (1000 * 60)) % 60
            val hr = (timeRemaining / (1000 * 60 * 60)) % 24
            binding.btnSleepTimerHeader.text = String.format(Locale.US, "⏱️ %02d:%02d:%02d", hr, min, sec)
            binding.btnSleepTimerHeader.setTextColor(0xFFFF9800.toInt())
        } else {
            binding.btnSleepTimerHeader.text = "مؤقت النوم ⏱️"
            binding.btnSleepTimerHeader.setTextColor(0xFFE2E8F0.toInt())
        }
    }

    private fun updateMiniPlayer() {
        val currentStation = viewModel.currentPlayingStation.value
        val service = radioService

        if (currentStation != null || (service != null && service.currentTitle != "راديو جو PRO")) {
            binding.miniPlayerContainer.visibility = View.VISIBLE
            binding.tvMiniStationName.text = currentStation?.name ?: service?.currentTitle

            if (currentStation != null) {
                binding.tvMiniCategoryBadge.text = getCategoryArabicTitle(currentStation.category)
                binding.tvMiniBitrateBadge.text = if (currentStation.bitrate > 0) "${currentStation.bitrate} Kbps" else "32 Kbps"
                binding.tvMiniCountryBadge.text = currentStation.countryCode
                binding.tvMiniServersBadge.text = "1 خادم"
            } else {
                binding.tvMiniCategoryBadge.text = "عام"
                binding.tvMiniBitrateBadge.text = "32 Kbps"
                binding.tvMiniCountryBadge.text = "EG"
                binding.tvMiniServersBadge.text = "1 خادم"
            }

            if (service != null) {
                binding.btnMiniPlayPause.text = if (service.isPlaying()) "إيقاف" else "تشغيل"
                binding.tvMiniDataMb.text = String.format(Locale.US, "%.2f MB", service.totalDataMb)
                binding.tvMiniDuration.text = service.getListeningDurationFormatted()
                binding.tvMiniSpeedKbps.text = String.format(Locale.US, "%.1f KB/s", service.currentSpeedKbps)
            }
        } else {
            binding.miniPlayerContainer.visibility = View.GONE
        }
    }

    private fun getCategoryArabicTitle(cat: String): String = when (cat) {
        "QURAN" -> "القرآن"
        "NEWS" -> "الأخبار"
        "MUSIC" -> "الموسيقى"
        "SPORTS" -> "الرياضة"
        "EGYPT" -> "مصر"
        else -> "عام"
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.stations.collectLatest { list ->
                stationAdapter.submitList(list)
                binding.tvEmptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            viewModel.categoryCounts.collectLatest { counts ->
                binding.chipAll.text = "🔥 الكل (${counts.allCount})"
                binding.chipFavorite.text = "⭐ المفضلة (${counts.favCount})"
                binding.chipEgypt.text = "🇪🇬 مصر (${counts.egyptCount})"
                binding.chipQuran.text = "📖 القرآن الكريم (${counts.quranCount})"
                binding.chipNews.text = "📰 الأخبار (${counts.newsCount})"
                binding.chipMusic.text = "🎵 الموسيقى (${counts.musicCount})"
                binding.chipSports.text = "⚽ الرياضة (${counts.sportsCount})"
            }
        }

        lifecycleScope.launch {
            viewModel.refreshResult.collectLatest { count ->
                // ✅ إخفاء الكورة البيضاء (إن وجدت) وإظهار رسالة نجاح
                binding.swipeRefreshLayout.isRefreshing = false
                Toast.makeText(this@MainActivity, "تم تحديث $count محطة بنجاح", Toast.LENGTH_SHORT).show()
            }
        }

        lifecycleScope.launch {
            viewModel.currentPlayingStation.collectLatest {
                updateMiniPlayer()
            }
        }
    }

    private fun showSleepTimerDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 50, 60, 30)
        }

        val etHours = EditText(this).apply {
            hint = "عدد الساعات (مثال: 1)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFCBD5E1.toInt())
            textSize = 18f
            setPadding(30, 30, 30, 30)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF334155.toInt())
                cornerRadius = 20f
                setStroke(3, 0xFFA78BFA.toInt())
            }
        }

        val etMinutes = EditText(this).apply {
            hint = "عدد الدقائق (مثال: 30)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFCBD5E1.toInt())
            textSize = 18f
            setPadding(30, 30, 30, 30)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF334155.toInt())
                cornerRadius = 20f
                setStroke(3, 0xFFA78BFA.toInt())
            }
        }

        val spacer = android.widget.Space(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 30)
        }

        layout.addView(etHours, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        layout.addView(spacer)
        layout.addView(etMinutes, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        radioService?.let { service ->
            if (service.sleepTimeRemainingMs > 0) {
                val totalMins = (service.sleepTimeRemainingMs / 1000 / 60).toInt()
                etHours.setText((totalMins / 60).toString())
                etMinutes.setText((totalMins % 60).toString())
            }
        }

        AlertDialog.Builder(this, R.style.Theme_RadioJooo)
            .setTitle("⏱️ ضبط مؤقت النوم (يدوي)")
            .setView(layout)
            .setPositiveButton("تشغيل") { _, _ ->
                val h = etHours.text.toString().toIntOrNull() ?: 0
                val m = etMinutes.text.toString().toIntOrNull() ?: 0
                val totalMinutes = (h * 60) + m

                if (totalMinutes > 0) {
                    radioService?.startSleepTimer(totalMinutes)
                    Toast.makeText(this, "تم ضبط المؤقت: $h ساعة و $m دقيقة", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "⚠️ الرجاء إدخال وقت صحيح", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("إلغاء المؤقت") { _, _ ->
                radioService?.startSleepTimer(0)
                updateSleepTimerButton()
                Toast.makeText(this, "تم إلغاء مؤقت النوم", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("رجوع", null)
            .show()
    }
}
