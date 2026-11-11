package com.example.radiojooo.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.radiojooo.data.Repo
import com.example.radiojooo.databinding.ActivityMainBinding
import com.example.radiojooo.player.PlaybackBus
import com.example.radiojooo.player.PlaybackUi
import com.example.radiojooo.player.RadioService
import com.example.radiojooo.player.Status
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@UnstableApi
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private lateinit var adapter: StationAdapter
    private var lastSync: Repo.SyncState = Repo.SyncState.IDLE

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        askNotificationPermission()

        adapter = StationAdapter(
            onClick = { RadioService.play(this, it.id) },
            onFavorite = { vm.toggleFavorite(it.id) }
        )
        binding.rvStations.layoutManager = LinearLayoutManager(this)
        binding.rvStations.adapter = adapter

        binding.swipeRefreshLayout.setOnRefreshListener { vm.refresh(); binding.swipeRefreshLayout.isRefreshing = false }
        binding.btnRefreshHeader.setOnClickListener { vm.refresh() }
        binding.btnSleepTimerHeader.setOnClickListener { showSleepDialog() }
        binding.btnMiniPlayPause.setOnClickListener { RadioService.toggle(this) }

        binding.etSearch.doOnTextChanged { text, _, _, _ ->
            val q = text?.toString().orEmpty()
            vm.searchQuery.value = q
            binding.btnClearSearch.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        }
        binding.btnClearSearch.setOnClickListener { binding.etSearch.setText("") }

        val chipToCategory = mapOf(
            binding.chipAll.id to "ALL", binding.chipFavorite.id to "FAVORITE", binding.chipEgypt.id to "EGYPT",
            binding.chipQuran.id to "QURAN", binding.chipNews.id to "NEWS", binding.chipMusic.id to "MUSIC",
            binding.chipSports.id to "SPORTS", binding.chipTalk.id to "TALK"
        )
        binding.chipGroupCategories.setOnCheckedStateChangeListener { _, ids ->
            vm.category.value = chipToCategory[ids.firstOrNull()] ?: "ALL"
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.stations.collect { list ->
                    adapter.submitList(list)
                    binding.tvEmptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                } }
                launch { vm.counts.collect { renderCounts(it) } }
                launch { vm.syncState.collect { renderSync(it) } }
                launch { PlaybackBus.state.collect { renderPlayer(it) } }
            }
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun renderCounts(c: Map<String, Int>) {
        binding.chipAll.text = "🔥 الكل (${c["ALL"] ?: 0})"
        binding.chipFavorite.text = "⭐ المفضلة (${c["FAVORITE"] ?: 0})"
        binding.chipEgypt.text = "🇪🇬 مصر (${c["EGYPT"] ?: 0})"
        binding.chipQuran.text = "📖 القرآن الكريم (${c["QURAN"] ?: 0})"
        binding.chipNews.text = "📰 الأخبار (${c["NEWS"] ?: 0})"
        binding.chipMusic.text = "🎵 الموسيقى (${c["MUSIC"] ?: 0})"
        binding.chipSports.text = "⚽ الرياضة (${c["SPORTS"] ?: 0})"
        binding.chipTalk.text = "🎙️ توك شو (${c["TALK"] ?: 0})"
    }

    private fun renderSync(s: Repo.SyncState) {
        when (s) {
            Repo.SyncState.SYNCING -> showIndicator("جاري تحديث الإذاعات...")
            Repo.SyncState.WARMING -> showIndicator("السحابة تجهّز القائمة لأول مرة، لحظات...")
            Repo.SyncState.FAILED -> showIndicator("تعذر الاتصال بالسحابة، تعمل القائمة المحفوظة")
            else -> binding.verificationIndicator.visibility = View.GONE
        }
        if (s == Repo.SyncState.UPDATED && lastSync != Repo.SyncState.UPDATED) {
            binding.tvUpdateSuccess.visibility = View.VISIBLE
            lifecycleScope.launch { delay(2500); binding.tvUpdateSuccess.visibility = View.GONE }
        }
        lastSync = s
    }

    private fun showIndicator(text: String) {
        binding.tvVerificationText.text = text
        binding.verificationIndicator.visibility = View.VISIBLE
    }

    private fun renderPlayer(ui: PlaybackUi) {
        val st = ui.station
        adapter.setPlaying(if (ui.status == Status.IDLE) null else st?.id)
        if (st == null || ui.status == Status.IDLE) {
            binding.miniPlayerContainer.visibility = View.GONE
            binding.healthProgressChip.visibility = View.GONE
        } else {
            binding.miniPlayerContainer.visibility = View.VISIBLE
            binding.tvMiniStationName.text = st.name
            binding.tvMiniCategoryBadge.text = StationAdapter.categoryName(st.category)
            binding.tvMiniCountryBadge.text = if (st.countryCode.isEmpty()) "AR" else st.countryCode
            binding.tvMiniBitrateBadge.text = if (ui.kbps > 0) "${ui.kbps} Kbps" else "— Kbps"
            binding.tvMiniServersBadge.text = "خادم ${ui.serverIndex}/${ui.serverCount}"
            binding.tvMiniSpeedKbps.text = String.format(Locale.US, "%.1f KB/s", ui.speedKBps)
            binding.tvMiniDuration.text = formatTime(ui.seconds)
            binding.tvMiniDataMb.text = String.format(Locale.US, "%.2f MB", ui.bytes / 1048576.0)
            val active = ui.status == Status.PLAYING || ui.status == Status.CONNECTING || ui.status == Status.SWITCHING
            binding.btnMiniPlayPause.text = if (active) "إيقاف" else "تشغيل"

            // reuse the progress chip to show server switching / healing live
            when (ui.status) {
                Status.SWITCHING -> showHealth("تبديل الخادم ${ui.serverIndex}/${ui.serverCount}", ui.serverIndex, ui.serverCount)
                Status.CONNECTING -> showHealth("جاري الاتصال...", 0, 0)
                Status.HEALING -> showHealth("البحث عن خوادم جديدة...", 0, 0)
                Status.ERROR -> showHealth("تعذر التشغيل، إعادة المحاولة تلقائياً...", 0, 0)
                else -> binding.healthProgressChip.visibility = View.GONE
            }
        }
        binding.btnSleepTimerHeader.text =
            if (ui.sleepRemainingSec > 0) "مؤقت النوم ⏱️ ${formatTime(ui.sleepRemainingSec).substring(3)}" else "مؤقت النوم ⏱️"
    }

    private fun showHealth(text: String, done: Int, total: Int) {
        binding.tvHealthProgress.text = text
        binding.pbHealth.isIndeterminate = total == 0
        if (total > 0) { binding.pbHealth.max = total; binding.pbHealth.progress = done }
        binding.healthProgressChip.visibility = View.VISIBLE
    }

    private fun showSleepDialog() {
        val labels = arrayOf("15 دقيقة", "30 دقيقة", "45 دقيقة", "ساعة", "ساعة ونصف", "إلغاء المؤقت")
        val minutes = intArrayOf(15, 30, 45, 60, 90, 0)
        AlertDialog.Builder(this)
            .setTitle("مؤقت النوم")
            .setItems(labels) { _, which -> RadioService.sleepTimer(this, minutes[which]) }
            .show()
    }

    private fun formatTime(totalSec: Long): String {
        val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }
}
