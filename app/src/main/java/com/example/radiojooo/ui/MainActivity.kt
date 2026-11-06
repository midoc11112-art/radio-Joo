package com.example.radiojooo.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
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
import com.example.radiojooo.data.repository.HealthCheckResult
import com.example.radiojooo.data.worker.StationSyncWorker
import com.example.radiojooo.databinding.ActivityMainBinding
import com.example.radiojooo.player.RadioService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit

@UnstableApi
class MainActivity : AppCompatActivity() {

    companion object {
        private const val HEALTH_CHANNEL_ID = "radio_joo_health_check_channel"
        private const val HEALTH_NOTIFICATION_ID = 2001
        private const val SYNC_WORK_NAME = "station_sync_worker"
    }

    private lateinit var binding: ActivityMainBinding
    private val viewModel: RadioViewModel by viewModels()
    private lateinit var stationAdapter: StationAdapter

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ -> }

    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateMiniPlayer()
            updateSleepTimerButton()
            updateHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAllPermissions()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupSearch()
        setupChipGroup()
        setupSwipeRefresh()
        setupHeaderButtons()
        setupMiniPlayer()
        observeViewModel()
        observeHealthCheck()
        createHealthNotificationChannel()
        scheduleStationSyncWorker()

        binding.btnStopCheck.setOnClickListener {
            viewModel.stopHealthCheck()
            binding.healthProgressLayout.visibility = View.GONE
            Toast.makeText(this, "تم إيقاف الفحص", Toast.LENGTH_SHORT).show()
        }

        if (savedInstanceState == null) {
            showVerificationIndicator()
        }
    }

    private fun scheduleStationSyncWorker() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val workRequest = PeriodicWorkRequestBuilder<StationSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                SYNC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                workRequest
            )
        } catch (t: Throwable) {
            // ignore
        }
    }

    private fun requestAllPermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.RECORD_AUDIO)
        if (perms.isNotEmpty()) requestPermissionLauncher.launch(perms.toTypedArray())
    }

    override fun onStart() { super.onStart(); RadioService.getInstance()?.setAppForegroundState(true); updateHandler.post(updateRunnable) }
    override fun onStop() { super.onStop(); RadioService.getInstance()?.setAppForegroundState(false); updateHandler.removeCallbacks(updateRunnable) }

    private fun setupRecyclerView() {
        stationAdapter = StationAdapter({ playStation(it) }, { viewModel.toggleFavorite(it) })
        binding.rvStations.apply { layoutManager = LinearLayoutManager(this@MainActivity); adapter = stationAdapter }
    }

    private fun setupSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.searchQuery.value = s?.toString() ?: ""
                binding.btnClearSearch.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        binding.btnClearSearch.setOnClickListener { binding.etSearch.text.clear() }
    }

    private fun setupChipGroup() {
        binding.chipGroupCategories.setOnCheckedChangeListener { _, checkedId ->
            viewModel.selectedCategory.value = when (checkedId) {
                R.id.chipFavorite -> "FAVORITE"; R.id.chipEgypt -> "EGYPT"; R.id.chipQuran -> "QURAN"
                R.id.chipNews -> "NEWS"; R.id.chipMusic -> "MUSIC"; R.id.chipSports -> "SPORTS"
                R.id.chipTalk -> "TALK"; else -> "ALL"
            }
        }
    }

    private fun setupSwipeRefresh() {
        binding.swipeRefreshLayout.setOnRefreshListener { binding.swipeRefreshLayout.isRefreshing = false; viewModel.refreshData() }
    }

    private fun setupHeaderButtons() {
        binding.btnRefreshHeader.setOnClickListener { viewModel.refreshData(); Toast.makeText(this, "جاري التحديث في الخلفية...", Toast.LENGTH_SHORT).show() }
        binding.btnSleepTimerHeader.setOnClickListener { showSleepTimerDialog() }
    }

    private fun setupMiniPlayer() {
        binding.miniPlayerHeader.setOnClickListener { startActivity(Intent(this, PlayerActivity::class.java)) }
        binding.btnMiniPlayPause.setOnClickListener {
            val service = RadioService.getInstance() ?: return@setOnClickListener
            if (service.isPlaying()) service.pausePlayback() else service.resumePlayback()
        }
    }

    private fun playStation(station: StationEntity) {
        viewModel.setCurrentStation(station)
        RadioService.requestPlayStation(this, station.name, station.uuid, station.primaryUrl, station.fallbackUrl, station.bitrate, station.category, station.countryCode)
        updateMiniPlayer()
    }

    private fun observeHealthCheck() {
        lifecycleScope.launch {
            combine(viewModel.healthChecked, viewModel.healthTotal) { c, t -> c to t }.collectLatest { (c, t) ->
                if (t > 0) { updateVerificationText(c, t); updateHealthProgressLayout(c, t) }
            }
        }
        lifecycleScope.launch { viewModel.healthComplete.collect { hideHealthProgressLayout(); showHealthCompleteNotification(it) } }
    }

    private fun updateVerificationText(checked: Int, total: Int) {
        binding.tvVerificationText.text = if (checked >= total && total > 0) "جاري التحقق من المحطات... $total/$total" else "جاري التحقق من المحطات... $checked/$total"
    }

    private fun updateHealthProgressLayout(checked: Int, total: Int) {
        val layout = binding.healthProgressLayout
        binding.tvHealthProgress.text = "فحص المحطات... $checked/$total"
        binding.pbHealth.progress = if (total > 0) (checked * 100 / total) else 0
        if (checked < total && layout.visibility != View.VISIBLE) {
            layout.visibility = View.VISIBLE
        } else if (checked >= total) {
            hideHealthProgressLayout()
        }
    }

    private fun hideHealthProgressLayout() {
        if (binding.healthProgressLayout.visibility == View.VISIBLE) {
            binding.healthProgressLayout.visibility = View.GONE
        }
    }

    private fun createHealthNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(HEALTH_CHANNEL_ID, "فحص المحطات", NotificationManager.IMPORTANCE_LOW).apply {
                description = "إشعارات عند اكتمال فحص المحطات"; setShowBadge(true); enableVibration(false); setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    private fun showHealthCompleteNotification(result: HealthCheckResult) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "✅ اكتمل فحص ${result.total} محطة — ${result.healthy} شغالة", Toast.LENGTH_LONG).show(); return
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, HEALTH_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_play)
            .setContentTitle("✅ اكتمل فحص المحطات")
            .setContentText("${result.total} محطة تم فحصها — ${result.healthy} شغالة")
            .setStyle(NotificationCompat.BigTextStyle().bigText("تم فحص ${result.total} محطة.\nالشغالة: ${result.healthy}\nالميتة: ${result.total - result.healthy}"))
            .setPriority(NotificationCompat.PRIORITY_LOW).setAutoCancel(true).setContentIntent(pi).build()
        try { getSystemService(NotificationManager::class.java)?.notify(HEALTH_NOTIFICATION_ID, n) }
        catch (_: SecurityException) { Toast.makeText(this, "✅ ${result.healthy}/${result.total} شغالة", Toast.LENGTH_LONG).show() }
    }

    private fun showVerificationIndicator() {
        val ind = binding.verificationIndicator
        if (ind.visibility == View.VISIBLE) return
        ind.alpha = 0f; ind.visibility = View.VISIBLE
        ind.animate().alpha(1f).setDuration(200).setInterpolator(DecelerateInterpolator()).withEndAction {
            ind.postDelayed({
                if (isFinishing || isDestroyed) return@postDelayed
                ind.animate().alpha(0f).setDuration(200).setInterpolator(AccelerateInterpolator()).withEndAction { if (!isFinishing && !isDestroyed) ind.visibility = View.GONE }.start()
            }, 1600L)
        }.start()
    }

    private fun updateSleepTimerButton() {
        val r = RadioService.getInstance()?.sleepTimeRemainingMs ?: 0L
        if (r > 0) {
            val s = (r / 1000) % 60; val m = (r / (1000 * 60)) % 60; val h = (r / (1000 * 60 * 60)) % 24
            binding.btnSleepTimerHeader.text = String.format(Locale.US, "⏱️ %02d:%02d:%02d", h, m, s)
            binding.btnSleepTimerHeader.setTextColor(0xFFFF9800.toInt())
        } else {
            binding.btnSleepTimerHeader.text = "مؤقت النوم ⏱️"
            binding.btnSleepTimerHeader.setTextColor(0xFFE2E8F0.toInt())
        }
    }

    private fun updateMiniPlayer() {
        val cs = viewModel.currentPlayingStation.value
        val service = RadioService.getInstance()
        if (cs != null || (service != null && service.currentTitle != "راديو جو PRO")) {
            binding.miniPlayerContainer.visibility = View.VISIBLE
            binding.tvMiniStationName.text = cs?.name ?: service?.currentTitle
            if (cs != null) {
                binding.tvMiniCategoryBadge.text = getCategoryArabicTitle(cs.category)
                binding.tvMiniBitrateBadge.text = if (cs.bitrate > 0) "${cs.bitrate} Kbps" else "غير محدد"
                binding.tvMiniCountryBadge.text = cs.countryCode
                binding.tvMiniServersBadge.text = "1 خادم"
            } else {
                binding.tvMiniCategoryBadge.text = "عام"
                binding.tvMiniBitrateBadge.text = "غير محدد"
                binding.tvMiniCountryBadge.text = "EG"
                binding.tvMiniServersBadge.text = "1 خادم"
            }
            if (service != null) {
                binding.btnMiniPlayPause.text = if (service.isPlaying()) "إيقاف" else "تشغيل"
                binding.tvMiniDataMb.text = String.format(Locale.US, "%.2f MB", service.totalDataMb)
                binding.tvMiniDuration.text = service.getListeningDurationFormatted()
                binding.tvMiniSpeedKbps.text = String.format(Locale.US, "%.1f KB/s", service.currentSpeedKbps)
            }
        } else binding.miniPlayerContainer.visibility = View.GONE
    }

    private fun getCategoryArabicTitle(cat: String) = when (cat) {
        "QURAN" -> "القرآن"; "NEWS" -> "الأخبار"; "MUSIC" -> "الموسيقى"; "SPORTS" -> "الرياضة"; "EGYPT" -> "مصر"; "TALK" -> "توك شو"; else -> "عام"
    }

    private fun observeViewModel() {
        lifecycleScope.launch { viewModel.stations.collectLatest { stationAdapter.submitList(it); binding.tvEmptyState.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE } }
        lifecycleScope.launch {
            viewModel.categoryCounts.collectLatest { c ->
                binding.chipAll.text = "🔥 الكل (${c.allCount})"; binding.chipFavorite.text = "⭐ المفضلة (${c.favCount})"
                binding.chipEgypt.text = "🇪🇬 مصر (${c.egyptCount})"; binding.chipQuran.text = "📖 القرآن (${c.quranCount})"
                binding.chipNews.text = "📰 الأخبار (${c.newsCount})"; binding.chipMusic.text = "🎵 الموسيقى (${c.musicCount})"
                binding.chipSports.text = "⚽ الرياضة (${c.sportsCount})"; binding.chipTalk.text = "🎙️ توك شو (${c.talkCount})"
            }
        }
        lifecycleScope.launch { viewModel.refreshResult.collectLatest { binding.swipeRefreshLayout.isRefreshing = false; Toast.makeText(this@MainActivity, "تم تحديث $it محطة", Toast.LENGTH_SHORT).show() } }
        lifecycleScope.launch { viewModel.currentPlayingStation.collectLatest { updateMiniPlayer() } }
    }

    private fun showSleepTimerDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(60, 50, 60, 30) }
        val etH = EditText(this).apply { hint = "ساعات"; inputType = InputType.TYPE_CLASS_NUMBER; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFFCBD5E1.toInt()); textSize = 18f; setPadding(30, 30, 30, 30); background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF334155.toInt()); cornerRadius = 20f; setStroke(3, 0xFFA78BFA.toInt()) } }
        val etM = EditText(this).apply { hint = "دقائق"; inputType = InputType.TYPE_CLASS_NUMBER; setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFFCBD5E1.toInt()); textSize = 18f; setPadding(30, 30, 30, 30); background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF334155.toInt()); cornerRadius = 20f; setStroke(3, 0xFFA78BFA.toInt()) } }
        layout.addView(etH, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        layout.addView(android.widget.Space(this).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 30) })
        layout.addView(etM, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        RadioService.getInstance()?.let { if (it.sleepTimeRemainingMs > 0) { val tm = (it.sleepTimeRemainingMs / 1000 / 60).toInt(); etH.setText((tm / 60).toString()); etM.setText((tm % 60).toString()) } }
        AlertDialog.Builder(this, R.style.Theme_RadioJooo).setTitle("⏱️ مؤقت النوم").setView(layout)
            .setPositiveButton("تشغيل") { _, _ ->
                val h = etH.text.toString().toIntOrNull() ?: 0; val m = etM.text.toString().toIntOrNull() ?: 0; val t = h * 60 + m
                if (t > 0) { RadioService.getInstance()?.startSleepTimer(t); Toast.makeText(this, "المؤقت: $h س $m د", Toast.LENGTH_SHORT).show() }
                else Toast.makeText(this, "⚠️ وقت غير صحيح", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء") { _, _ -> RadioService.getInstance()?.startSleepTimer(0); updateSleepTimerButton(); Toast.makeText(this, "أُلغي", Toast.LENGTH_SHORT).show() }
            .setNeutralButton("رجوع", null).show()
    }
}