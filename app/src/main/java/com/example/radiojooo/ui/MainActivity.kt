package com.example.radiojooo.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.radiojooo.Graph
import com.example.radiojooo.R
import com.example.radiojooo.data.Repo
import com.example.radiojooo.databinding.ActivityMainBinding
import com.example.radiojooo.databinding.ItemServerBinding
import com.example.radiojooo.player.PlaybackBus
import com.example.radiojooo.player.PlaybackUi
import com.example.radiojooo.player.RadioService
import com.example.radiojooo.player.Status
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@UnstableApi
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val vm: MainViewModel by viewModels()
    private lateinit var adapter: StationAdapter
    private lateinit var layoutManager: LinearLayoutManager
    private var messageJob: Job? = null
    private var refreshLabel = ""

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val storagePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        askNotificationPermission()
        askStorageAccess()
        refreshLabel = binding.btnRefreshHeader.text.toString()

        adapter = StationAdapter(
            onClick = { RadioService.play(this, it.id) },
            onFavorite = { vm.toggleFavorite(it.id) },
            onMenu = { id, name -> showStationMenu(id, name) },
            onOrder = { ids -> vm.commitOrder(ids) },
            onDragState = { dragging -> binding.swipeRefreshLayout.isEnabled = !dragging },
            onSelectionChanged = { count -> updateSelectionBar(count) }
        )
        layoutManager = LinearLayoutManager(this)
        binding.rvStations.layoutManager = layoutManager
        binding.rvStations.adapter = adapter
        androidx.recyclerview.widget.ItemTouchHelper(StationAdapter.TouchCallback(adapter, android.view.ViewConfiguration.get(this).scaledTouchSlop)).attachToRecyclerView(binding.rvStations)
        setupSelectionBar()

        binding.chipEgypt.visibility = View.GONE
        val btnLiveSearch = android.widget.Button(this).apply {
            text = "🌐 بحث حي على الإنترنت عن إذاعة جديدة..."
            textSize = 14f
            isAllCaps = false
            setTextColor(android.graphics.Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#1D4ED8"))
                cornerRadius = 14f * resources.displayMetrics.density
            }
            val pad = (10 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            visibility = View.GONE
            setOnClickListener {
                val q = binding.etSearch.text?.toString()?.trim().orEmpty()
                if (q.length >= 2) {
                    vm.searchOnline(q)
                    showLiveSearchDialog(q)
                }
            }
        }
        (binding.rvStations.parent as? android.view.ViewGroup)?.let { parent ->
            val idx = parent.indexOfChild(binding.rvStations)
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val m = (8 * resources.displayMetrics.density).toInt()
                setMargins(m, m / 2, m, m / 2)
            }
            parent.addView(btnLiveSearch, idx.coerceAtLeast(0), lp)
        }
        binding.etSearch.doOnTextChanged { text, _, _, _ ->
            val q = text?.toString()?.trim().orEmpty()
            if (q.length >= 2) {
                btnLiveSearch.text = "🌐 بحث حي على الإنترنت عن \"$q\""
                btnLiveSearch.visibility = View.VISIBLE
            } else {
                btnLiveSearch.visibility = View.GONE
            }
        }


        // jump back to the top of the list in one tap
        binding.rvStations.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                val show = layoutManager.findFirstVisibleItemPosition() > 5
                binding.fabScrollTop.visibility = if (show) View.VISIBLE else View.GONE
            }
        })
        binding.fabScrollTop.setOnClickListener {
            layoutManager.scrollToPositionWithOffset(0, 0)
            binding.fabScrollTop.visibility = View.GONE
        }

        binding.swipeRefreshLayout.setOnRefreshListener { vm.refresh(); binding.swipeRefreshLayout.isRefreshing = false }
        binding.btnRefreshHeader.setOnClickListener { vm.refresh() }
        binding.btnSleepTimerHeader.setOnClickListener {
            val ui = PlaybackBus.state.value
            if (ui.station == null) Toast.makeText(this, "\u0634\u063A\u0651\u0644 \u0625\u0630\u0627\u0639\u0629 \u0623\u0648\u0644\u0627\u064B \u062B\u0645 \u0627\u0636\u0628\u0637 \u0627\u0644\u0645\u0624\u0642\u062A", Toast.LENGTH_SHORT).show()
            else SleepDialog.show(this, ui.sleepRemainingSec)
        }
        binding.btnMiniPlayPause.setOnClickListener { RadioService.toggle(this) }
        binding.tvMiniServersBadge.isEnabled = true
        binding.tvMiniServersBadge.isClickable = true
        binding.tvMiniServersBadge.setOnClickListener { showServersDialog() }
        binding.miniPlayerContainer.setOnClickListener { openPlayer() }
        binding.miniPlayerHeader.setOnClickListener { openPlayer() }

        binding.etSearch.doOnTextChanged { text, _, _, _ ->
            val q = text?.toString().orEmpty()
            vm.searchQuery.value = q
            binding.btnClearSearch.visibility = if (q.isEmpty()) View.GONE else View.VISIBLE
        }
        binding.btnClearSearch.setOnClickListener { binding.etSearch.setText("") }

        val chipToCategory = mapOf(
            binding.chipAll.id to "ALL", binding.chipFavorite.id to "FAVORITE", 
            binding.chipQuran.id to "QURAN", binding.chipNews.id to "NEWS", binding.chipMusic.id to "MUSIC",
            binding.chipSports.id to "SPORTS", binding.chipTalk.id to "TALK"
        )
        binding.chipGroupCategories.setOnCheckedStateChangeListener { _, ids ->
            vm.category.value = chipToCategory[ids.firstOrNull()] ?: "ALL"
            layoutManager.scrollToPositionWithOffset(0, 0)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { vm.stations.collect { list ->
                    adapter.submitList(list)
                    binding.tvEmptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                } }
                launch { vm.counts.collect { renderCounts(it) } }
                launch { vm.syncState.collect { renderSync(it) } }
                launch { vm.refreshing.collect { renderRefreshing(it) } }
                launch { vm.messages.collect { showMessage(it) } }
                launch { PlaybackBus.state.collect { renderPlayer(it) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (Graph.repo(this).restoreFromExternalBackup()) {
            showMessage("✅ تم استعادة ترتيب وإعدادات الإذاعات المحفوظة")
        }
    }

    // ---------------------------------------------------------------- recordings folder access (/sdcard/RadioJoo)
    private fun askStorageAccess() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) return
                val sp = getSharedPreferences("radio_ui", MODE_PRIVATE)
                val asked = sp.getInt("storage_prompts", 0)
                if (asked >= 3) return
                sp.edit().putInt("storage_prompts", asked + 1).apply()
                MaterialAlertDialogBuilder(this, R.style.RadioDialog)
                    .setTitle("\u0645\u062C\u0644\u062F \u0627\u0644\u062A\u0633\u062C\u064A\u0644\u0627\u062A")
                    .setMessage("\u0644\u062D\u0641\u0638 \u0627\u0644\u062A\u0633\u062C\u064A\u0644\u0627\u062A \u0641\u064A \u0645\u062C\u0644\u062F RadioJoo \u0627\u0644\u0638\u0627\u0647\u0631 \u0641\u064A \u0630\u0627\u0643\u0631\u0629 \u0627\u0644\u0647\u0627\u062A\u0641 \u0627\u0644\u0631\u0626\u064A\u0633\u064A\u0629 (\u0644\u064A\u0633\u0647\u0644 \u0627\u0644\u0648\u0635\u0648\u0644 \u0625\u0644\u064A\u0647\u0627 \u0645\u0646 \u0623\u064A \u0645\u062F\u064A\u0631 \u0645\u0644\u0641\u0627\u062A)\u060C \u0627\u0633\u0645\u062D \u0644\u0644\u062A\u0637\u0628\u064A\u0642 \u0628\u0627\u0644\u0648\u0635\u0648\u0644 \u0625\u0644\u0649 \u0627\u0644\u0645\u0644\u0641\u0627\u062A.")
                    .setPositiveButton("\u0627\u0644\u0633\u0645\u0627\u062D") { _, _ -> openAllFilesSettings() }
                    .setNegativeButton("\u0644\u0627\u062D\u0642\u0627\u064B", null)
                    .show()
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } catch (e: Exception) { /* never block the app for this */ }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun openAllFilesSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (e2: Exception) { }
        }
    }

    // ---------------------------------------------------------------- servers popup (mini player)
    private fun showServersDialog() {
        val ui = PlaybackBus.state.value
        val st = ui.station ?: return
        val health = Graph.health(this)
        val d = resources.displayMetrics.density

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding((16 * d).toInt(), (8 * d).toInt(), (16 * d).toInt(), (4 * d).toInt())

        val hint = TextView(this)
        hint.text = "\u0627\u0636\u063A\u0637 \u0639\u0644\u0649 \u062E\u0627\u062F\u0645 \u0644\u062A\u0634\u063A\u064A\u0644\u0647 \u0641\u0648\u0631\u0627\u064B \u2022 \u0627\u0636\u063A\u0637 \u0645\u0637\u0648\u0644\u0627\u064B \u0644\u0644\u062A\u062B\u0628\u064A\u062A \u2705 \u0623\u0648 \u0627\u0644\u062D\u0638\u0631 \u274C"
        hint.setTextColor(0xFF94A3B8.toInt())
        hint.textSize = 12f
        hint.setPadding(0, 0, 0, (8 * d).toInt())
        root.addView(hint)

        val list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val scroll = ScrollView(this)
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        var dialog: AlertDialog? = null
        fun reopen() { dialog?.dismiss(); showServersDialog() }

        fun stateText(url: String, current: Boolean): String {
            val sb = StringBuilder()
            if (health.isPinned(url)) sb.append("\u2705 ")
            if (current) sb.append(if (ui.status == Status.PLAYING) "\u25B6" else "\u2026")
            return sb.toString().trim()
        }

        ui.servers.forEachIndexed { i, s ->
            if (health.isBanned(s.url)) return@forEachIndexed
            val host = Uri.parse(s.url).host ?: s.url
            val row = ItemServerBinding.inflate(LayoutInflater.from(this), list, false)
            row.tvSrvIndex.text = "${i + 1}"
            row.tvSrvHost.text = host
            row.tvSrvKbps.text = if (s.kbps > 0) "${s.kbps} Kbps" else "\u2014 Kbps"
            row.tvSrvState.text = stateText(s.url, i == ui.currentIndex)
            row.root.setOnClickListener {
                RadioService.selectServer(this, i)
                dialog?.dismiss()
            }
            row.root.setOnLongClickListener {
                val pinnedNow = health.isPinned(s.url)
                MaterialAlertDialogBuilder(this, R.style.RadioDialog)
                    .setTitle(host)
                    .setItems(arrayOf(if (pinnedNow) "\u0625\u0644\u063A\u0627\u0621 \u0627\u0644\u062A\u062B\u0628\u064A\u062A" else "\u062A\u062B\u0628\u064A\u062A \u2705 (\u0623\u0648\u0644\u0648\u064A\u0629 \u0645\u0637\u0644\u0642\u0629)", "\u062D\u0638\u0631 \u274C (\u062A\u062C\u0627\u0647\u0644 \u062F\u0627\u0626\u0645)")) { _, which ->
                        if (which == 0) {
                            health.setPinned(s.url, !pinnedNow)
                            Toast.makeText(this, if (pinnedNow) "\u062A\u0645 \u0625\u0644\u063A\u0627\u0621 \u0627\u0644\u062A\u062B\u0628\u064A\u062A" else "\u062A\u0645 \u062A\u062B\u0628\u064A\u062A \u0627\u0644\u062E\u0627\u062F\u0645 \u2705", Toast.LENGTH_SHORT).show()
                        } else {
                            health.setBanned(s.url, true)
                            Toast.makeText(this, "\u062A\u0645 \u062D\u0638\u0631 \u0627\u0644\u062E\u0627\u062F\u0645 \u274C \u0648\u0644\u0646 \u064A\u064F\u0633\u062A\u062E\u062F\u0645 \u0628\u0639\u062F \u0627\u0644\u0622\u0646", Toast.LENGTH_SHORT).show()
                        }
                        reopen()
                    }
                    .setNegativeButton("\u0625\u0644\u063A\u0627\u0621", null)
                    .show()
                true
            }
            list.addView(row.root)
        }

        // banned servers stay visible at the bottom so they can be un-banned with one tap
        (ui.servers + st.servers).distinctBy { it.url }.filter { health.isBanned(it.url) }.forEach { s ->
            val row = ItemServerBinding.inflate(LayoutInflater.from(this), list, false)
            row.tvSrvIndex.text = "-"
            row.tvSrvHost.text = Uri.parse(s.url).host ?: s.url
            row.tvSrvKbps.text = if (s.kbps > 0) "${s.kbps} Kbps" else "\u2014 Kbps"
            row.tvSrvState.text = "\u274C"
            row.root.alpha = 0.55f
            row.root.setOnClickListener {
                health.setBanned(s.url, false)
                Toast.makeText(this, "\u062A\u0645 \u0625\u0644\u063A\u0627\u0621 \u0627\u0644\u062D\u0638\u0631\u060C \u0633\u064A\u0639\u0648\u062F \u0627\u0644\u062E\u0627\u062F\u0645 \u0644\u0644\u062A\u0631\u062A\u064A\u0628 \u0639\u0646\u062F \u0627\u0644\u062A\u0634\u063A\u064A\u0644 \u0627\u0644\u0642\u0627\u062F\u0645", Toast.LENGTH_SHORT).show()
                reopen()
            }
            list.addView(row.root)
        }

        if (list.childCount == 0) {
            val empty = TextView(this)
            empty.text = "\u0644\u0627 \u062A\u0648\u062C\u062F \u062E\u0648\u0627\u062F\u0645 \u0644\u0647\u0630\u0647 \u0627\u0644\u0625\u0630\u0627\u0639\u0629"
            empty.setTextColor(0xFFE2E8F0.toInt())
            list.addView(empty)
        }

        dialog = MaterialAlertDialogBuilder(this, R.style.RadioDialog)
            .setTitle("\u0627\u0644\u062E\u0648\u0627\u062F\u0645 \u2022 ${st.name}")
            .setView(root)
            .setPositiveButton("\u0625\u063A\u0644\u0627\u0642", null)
            .show()
    }

    private fun showStationMenu(id: String, name: String) {
        MaterialAlertDialogBuilder(this, R.style.RadioDialog)
            .setTitle(name)
            .setItems(arrayOf(
                "\u062A\u062D\u062F\u064A\u062B \u062E\u0648\u0627\u062F\u0645 \u0647\u0630\u0647 \u0627\u0644\u0625\u0630\u0627\u0639\u0629 \uD83D\uDD04",
                "\u0625\u0639\u0627\u062F\u0629 \u062A\u0633\u0645\u064A\u0629 \u0627\u0644\u0625\u0630\u0627\u0639\u0629 \u270F\uFE0F",
                "\u0646\u0642\u0644 \u0644\u0642\u0633\u0645 \u0622\u062E\u0631 \uD83D\uDCC2",
                "\u0625\u0632\u0627\u0644\u0629 \u0645\u0646 \u0627\u0644\u0642\u0633\u0645 (\u062A\u0631\u0643\u0647\u0627 \u0641\u064A \u0627\u0644\u0643\u0644) \uD83D\uDCE4",
                "\u062A\u062D\u062F\u064A\u062F \u2611\uFE0F",
                "\u062D\u0630\u0641 \u0627\u0644\u0625\u0630\u0627\u0639\u0629 \u0646\u0647\u0627\u0626\u064A\u0627\u064B \uD83D\uDDD1\uFE0F"
            )) { _, which ->
                when (which) {
                    0 -> vm.refreshStation(id)
                    1 -> showRenameDialog(id, name)
                    2 -> showMoveCategoryDialog(id, name)
                    3 -> vm.changeStationsCategoryBatch(listOf(id), "ALL")
                    4 -> adapter.enterSelectionMode(id)
                    else -> confirmDeleteStation(id, name)
                }
            }
            .setNegativeButton("\u0625\u0644\u063A\u0627\u0621", null)
            .show()
    }

    private fun showRenameDialog(id: String, currentName: String) {
        val input = android.widget.EditText(this).apply {
            setText(currentName)
            setSelection(currentName.length)
            setTextColor(android.graphics.Color.WHITE)
            setHintTextColor(android.graphics.Color.GRAY)
            setHint("\u0627\u0643\u062A\u0628 \u0627\u0644\u0627\u0633\u0645 \u0627\u0644\u062C\u062F\u064A\u062F (\u0623\u0648 \u0627\u062A\u0631\u0643\u0647 \u0641\u0627\u0631\u063A\u0627\u064B \u0644\u0644\u0623\u0635\u0644\u064A)")
            setPadding(48, 32, 48, 32)
        }
        MaterialAlertDialogBuilder(this, R.style.RadioDialog)
            .setTitle("\u0625\u0639\u0627\u062F\u0629 \u062A\u0633\u0645\u064A\u0629 \u0627\u0644\u0625\u0630\u0627\u0639\u0629")
            .setView(input)
            .setPositiveButton("\u062D\u0641\u0638") { _, _ -> vm.renameStation(id, input.text.toString()) }
            .setNegativeButton("\u0625\u0644\u063A\u0627\u0621", null)
            .show()
    }

    
    private fun showLiveSearchDialog(query: String) {
        val progress = android.widget.ProgressBar(this).apply { isIndeterminate = true }
        val loadingDlg = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("🌐 جاري البحث الحي على الإنترنت...")
            .setMessage("البحث بالاسم والتردد والوصف عن: $query")
            .setView(progress)
            .setNegativeButton("إلغاء", null)
            .show()

        lifecycleScope.launch {
            vm.liveSearching.collect { searching ->
                if (!searching && vm.liveResults.value.isNotEmpty()) {
                    loadingDlg.dismiss()
                    val list = vm.liveResults.value
                    val items = list.map { st ->
                        val br = st.servers.firstOrNull()?.kbps ?: 64
                        val catAr = com.example.radiojooo.data.CategoryStyle.arabicName(st.category)
                        "${st.name}  •  [$catAr | ${br}kbps]"
                    }.toTypedArray()
                    androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("نتائج البحث الحي (${list.size}) - اضغط للإضافة والتشغيل")
                        .setItems(items) { _, which ->
                            val chosen = list[which]
                            vm.addLiveStation(chosen) { saved ->
                                com.example.radiojooo.player.RadioService.play(this@MainActivity, saved)
                                startActivity(Intent(this@MainActivity, PlayerActivity::class.java))
                            }
                        }
                        .setNegativeButton("إغلاق", null)
                        .show()
                    vm.clearLiveSearch()
                } else if (!searching && loadingDlg.isShowing) {
                    loadingDlg.dismiss()
                }
            }
        }
    }

    private fun showMoveCategoryDialog(id: String, name: String) {
        val keys = arrayOf("QURAN", "NEWS", "MUSIC", "SPORTS", "TALK", "ALL")
        val labels = arrayOf(
                        com.example.radiojooo.data.CategoryStyle.arabicName("QURAN"),
            com.example.radiojooo.data.CategoryStyle.arabicName("NEWS"),
            com.example.radiojooo.data.CategoryStyle.arabicName("MUSIC"),
            com.example.radiojooo.data.CategoryStyle.arabicName("SPORTS"),
            com.example.radiojooo.data.CategoryStyle.arabicName("TALK"),
            "\u0625\u0632\u0627\u0644\u0629 \u0645\u0646 \u0627\u0644\u0642\u0633\u0645 (\u062A\u0631\u0643\u0647\u0627 \u0641\u064A \u0627\u0644\u0643\u0644 \u0641\u0642\u0637)"
        )
        val currentCat = vm.stationCategory(id)
        var selectedIdx = keys.indexOf(currentCat).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this, R.style.RadioDialog)
            .setTitle("\u0646\u0642\u0644: $name")
            .setSingleChoiceItems(labels, selectedIdx) { _, which -> selectedIdx = which }
            .setPositiveButton("\u0645\u0648\u0627\u0641\u0642") { _, _ ->
                if (selectedIdx in keys.indices) vm.changeStationCategory(id, keys[selectedIdx])
            }
            .setNegativeButton("\u0625\u0644\u063A\u0627\u0621", null)
            .show()
    }

    private var selectionBarView: android.widget.LinearLayout? = null
    private var selectionCountTv: android.widget.TextView? = null

    private fun setupSelectionBar() {
        val rootGroup = binding.root as? android.view.ViewGroup ?: return
        selectionBarView?.let { rootGroup.removeView(it) }
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val bar = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 24f * d
                setColor(android.graphics.Color.parseColor("#0F172A"))
                setStroke(dp(2), android.graphics.Color.parseColor("#8B5CF6"))
            }
            setPadding(dp(14), dp(12), dp(14), dp(14))
            elevation = 64f
            visibility = View.GONE
        }

        // Top row: Selected count + Select All + Close
        val topRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }

        val tvCount = android.widget.TextView(this).apply {
            setTextColor(android.graphics.Color.parseColor("#38BDF8"))
            textSize = 15f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        fun mkTopBtn(label: String, bgHex: String, onClick: () -> Unit): android.widget.TextView {
            return android.widget.TextView(this).apply {
                text = label
                setTextColor(android.graphics.Color.WHITE)
                textSize = 13f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 12f * d
                    setColor(android.graphics.Color.parseColor(bgHex))
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(8) }
                setOnClickListener { onClick() }
            }
        }

        val btnAll = mkTopBtn("☑️ تحديد الكل", "#334155") { adapter.selectAllVisible() }
        val btnCancel = mkTopBtn("✕ إلغاء", "#475569") { adapter.exitSelectionMode() }

        topRow.addView(tvCount)
        topRow.addView(btnAll)
        topRow.addView(btnCancel)

        // Bottom row: 3 large, well-spaced action buttons with clear labels
        val actionsRow = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        fun mkActionBtn(label: String, bgHex: String, addMarginStart: Boolean, onClick: () -> Unit): android.widget.TextView {
            return android.widget.TextView(this).apply {
                text = label
                setTextColor(android.graphics.Color.WHITE)
                textSize = 13.5f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = android.view.Gravity.CENTER
                maxLines = 1
                setPadding(dp(8), dp(12), dp(8), dp(12))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14f * d
                    setColor(android.graphics.Color.parseColor(bgHex))
                }
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    if (addMarginStart) marginStart = dp(10)
                }
                setOnClickListener { onClick() }
            }
        }

        val btnMoveCat = mkActionBtn("📁 نقل لقسم", "#2563EB", false) {
            val sel = adapter.getSelectedIds()
            if (sel.isNotEmpty()) showBatchMoveCategoryDialog(sel)
        }
        val btnUnassign = mkActionBtn("📤 للكل فقط", "#D97706", true) {
            val sel = adapter.getSelectedIds()
            if (sel.isNotEmpty()) {
                vm.changeStationsCategoryBatch(sel, "ALL")
                adapter.exitSelectionMode()
            }
        }
        val btnDel = mkActionBtn("🗑️ حذف", "#DC2626", true) {
            val sel = adapter.getSelectedIds()
            if (sel.isNotEmpty()) confirmBatchDeleteStations(sel)
        }

        actionsRow.addView(btnMoveCat)
        actionsRow.addView(btnUnassign)
        actionsRow.addView(btnDel)

        bar.addView(topRow)
        bar.addView(actionsRow)

        if (rootGroup is android.widget.RelativeLayout) {
            val lp = android.widget.RelativeLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
            lp.bottomMargin = dp(12)
            lp.marginStart = dp(12)
            lp.marginEnd = dp(12)
            rootGroup.addView(bar, lp)
        } else {
            rootGroup.addView(bar)
        }
        bar.bringToFront()
        selectionBarView = bar
        selectionCountTv = tvCount
    }

    private fun updateSelectionBar(count: Int) {
        if (selectionBarView == null) setupSelectionBar()
        val bar = selectionBarView ?: return
        if (!adapter.selectionMode) {
            bar.visibility = View.GONE
            return
        }
        val d = resources.displayMetrics.density
        val lp = bar.layoutParams as? android.widget.RelativeLayout.LayoutParams
        if (lp != null) {
            lp.bottomMargin = if (binding.miniPlayerContainer.visibility == View.VISIBLE) {
                (binding.miniPlayerContainer.height.takeIf { it > 0 } ?: (68 * d).toInt()) + (14 * d).toInt()
            } else {
                (12 * d).toInt()
            }
            bar.layoutParams = lp
        }
        bar.visibility = View.VISIBLE
        bar.bringToFront()
        selectionCountTv?.text = "المحدد: $count إذاعة"
    }

    private fun showBatchMoveCategoryDialog(ids: List<String>) {
        val keys = arrayOf("QURAN", "NEWS", "MUSIC", "SPORTS", "TALK")
        val labels = keys.map { com.example.radiojooo.data.CategoryStyle.arabicName(it) }.toTypedArray()
        var selectedIdx = 0
        MaterialAlertDialogBuilder(this, R.style.RadioDialog)
            .setTitle("\u0646\u0642\u0644 ${ids.size} \u0625\u0630\u0627\u0639\u0627\u062A \u0625\u0644\u0649 \u0642\u0633\u0645:")
            .setSingleChoiceItems(labels, selectedIdx) { _, which -> selectedIdx = which }
            .setPositiveButton("\u0645\u0648\u0627\u0641\u0642") { _, _ ->
                if (selectedIdx in keys.indices) {
                    vm.changeStationsCategoryBatch(ids, keys[selectedIdx])
                    adapter.exitSelectionMode()
                }
            }
            .setNegativeButton("\u0625\u0644\u063A\u0627\u0621", null)
            .show()
    }

    private fun confirmBatchDeleteStations(ids: List<String>) {
        MaterialAlertDialogBuilder(this, R.style.RadioDialog)
            .setTitle("\u062D\u0630\u0641 \$ids.size \u0625\u0630\u0627\u0639\u0627\u062A \u0646\u0647\u0627\u0646\u064A\u0627\u064B\u061F")
            .setMessage("\u0633\u064A\u062A\u0645 \u062D\u0630\u0641 \u0643\u0644 \u0627\u0644\u0625\u0630\u0627\u0639\u0627\u062A \u0627\u0644\u0645\u062D\u062F\u062F\u0629 \u0645\u0646 \u0627\u0644\u062A\u0637\u0628\u064A\u062F \u0648\u0627\u0644\u0633\u064A\u0641\u0631 \u0648\u0644\u0646 \u062A\u0638\u0647\u0631 \u0645\u0631\u0629 \u0623\u062E\u0631\u0649.")
            .setPositiveButton("\u062D\u0630\u0641 \u0627\u0644\u0643\u0644") { _, _ ->
                vm.deleteStationsBatch(ids)
                adapter.exitSelectionMode()
            }
            .setNegativeButton("\u0625\u0644\u063A\u0627\u0621", null)
            .show()
    }

    private fun confirmDeleteStation(id: String, name: String) {
        MaterialAlertDialogBuilder(this, R.style.RadioDialog)
            .setTitle("\u062D\u0630\u0641 \u0627\u0644\u0625\u0630\u0627\u0639\u0629 \u0646\u0647\u0627\u0626\u064A\u0627\u064B\u061F")
            .setMessage("$name\n\n\u0633\u064A\u062A\u0645 \u062D\u0630\u0641 \u0627\u0644\u0625\u0630\u0627\u0639\u0629 \u0646\u0647\u0627\u0626\u064A\u0627\u064B \u0648\u0644\u0646 \u062A\u0638\u0647\u0631 \u0645\u0631\u0629 \u0623\u062E\u0631\u0649.")
            .setPositiveButton("\u062D\u0630\u0641") { _, _ -> vm.deleteStation(id) }
            .setNegativeButton("\u0625\u0644\u063A\u0627\u0621", null)
            .show()
    }

    private fun openPlayer() {
        if (PlaybackBus.state.value.station != null) startActivity(Intent(this, PlayerActivity::class.java))
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun renderCounts(c: Map<String, Int>) {
        binding.chipAll.text = "\uD83D\uDD25 \u0627\u0644\u0643\u0644 (${c["ALL"] ?: 0})"
        binding.chipFavorite.text = "\u2B50 \u0627\u0644\u0645\u0641\u0636\u0644\u0629 (${c["FAVORITE"] ?: 0})"
        binding.chipEgypt.visibility = View.GONE
        binding.chipQuran.text = "\uD83D\uDCD6 \u0627\u0644\u0642\u0631\u0622\u0646 \u0627\u0644\u0643\u0631\u064A\u0645 (${c["QURAN"] ?: 0})"
        binding.chipNews.text = "\uD83D\uDCF0 \u0627\u0644\u0623\u062E\u0628\u0627\u0631 (${c["NEWS"] ?: 0})"
        binding.chipMusic.text = "\uD83C\uDFB5 \u0627\u0644\u0645\u0648\u0633\u064A\u0642\u0649 (${c["MUSIC"] ?: 0})"
        binding.chipSports.text = "\u26BD \u0627\u0644\u0631\u064A\u0627\u0636\u0629 (${c["SPORTS"] ?: 0})"
        binding.chipTalk.text = "\uD83C\uDF99\uFE0F \u062A\u0648\u0643 \u0634\u0648 (${c["TALK"] ?: 0})"
    }

    private fun renderSync(s: Repo.SyncState) {
        when (s) {
            Repo.SyncState.SYNCING -> showIndicator("\u062C\u0627\u0631\u064A \u062A\u062D\u062F\u064A\u062B \u0627\u0644\u0625\u0630\u0627\u0639\u0627\u062A...")
            Repo.SyncState.WARMING -> showIndicator("\u0627\u0644\u0633\u062D\u0627\u0628\u0629 \u062A\u062C\u0647\u0651\u0632 \u0627\u0644\u0642\u0627\u0626\u0645\u0629 \u0644\u0623\u0648\u0644 \u0645\u0631\u0629\u060C \u0644\u062D\u0638\u0627\u062A...")
            Repo.SyncState.FAILED -> showIndicator("\u062A\u0639\u0630\u0631 \u0627\u0644\u0627\u062A\u0635\u0627\u0644 \u0628\u0627\u0644\u0633\u062D\u0627\u0628\u0629\u060C \u062A\u0639\u0645\u0644 \u0627\u0644\u0642\u0627\u0626\u0645\u0629 \u0627\u0644\u0645\u062D\u0641\u0648\u0638\u0629")
            else -> binding.verificationIndicator.visibility = View.GONE
        }
    }

    private fun renderRefreshing(on: Boolean) {
        binding.btnRefreshHeader.isEnabled = !on
        binding.btnRefreshHeader.text = if (on) "\u062C\u0627\u0631\u064A \u0627\u0644\u0641\u062D\u0635..." else refreshLabel
    }

    private fun showMessage(text: String) {
        messageJob?.cancel()
        binding.tvUpdateSuccess.text = text
        binding.tvUpdateSuccess.visibility = View.VISIBLE
        messageJob = lifecycleScope.launch { delay(5000); binding.tvUpdateSuccess.visibility = View.GONE }
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
            binding.tvMiniBitrateBadge.text = if (ui.kbps > 0) "${ui.kbps} Kbps" else "\u2014 Kbps"
            binding.tvMiniServersBadge.text = "\u062E\u0627\u062F\u0645 ${ui.serverIndex}/${ui.serverCount} \u25BE"
            binding.tvMiniSpeedKbps.text = String.format(Locale.US, "%.1f KB/s", ui.speedKBps)
            binding.tvMiniDuration.text = formatTime(ui.seconds)
            binding.tvMiniDataMb.text = String.format(Locale.US, "%.2f MB", ui.bytes / 1048576.0)
            val active = ui.status == Status.PLAYING || ui.status == Status.CONNECTING || ui.status == Status.SWITCHING
            binding.btnMiniPlayPause.text = if (active) "\u0625\u064A\u0642\u0627\u0641" else "\u062A\u0634\u063A\u064A\u0644"

            when (ui.status) {
                Status.SWITCHING -> showHealth("\u062A\u0628\u062F\u064A\u0644 \u0627\u0644\u062E\u0627\u062F\u0645 ${ui.serverIndex}/${ui.serverCount}", ui.serverIndex, ui.serverCount)
                Status.CONNECTING -> showHealth("\u062C\u0627\u0631\u064A \u0627\u0644\u0627\u062A\u0635\u0627\u0644...", 0, 0)
                Status.HEALING -> showHealth("\u0627\u0644\u0628\u062D\u062B \u0639\u0646 \u062E\u0648\u0627\u062F\u0645 \u062C\u062F\u064A\u062F\u0629...", 0, 0)
                Status.ERROR -> showHealth("\u062A\u0639\u0630\u0631 \u0627\u0644\u062A\u0634\u063A\u064A\u0644\u060C \u0625\u0639\u0627\u062F\u0629 \u0627\u0644\u0645\u062D\u0627\u0648\u0644\u0629 \u062A\u0644\u0642\u0627\u0626\u064A\u0627\u064B...", 0, 0)
                else -> binding.healthProgressChip.visibility = View.GONE
            }
        }
        binding.btnSleepTimerHeader.text =
            if (ui.sleepRemainingSec > 0) "\u0645\u0624\u0642\u062A \u0627\u0644\u0646\u0648\u0645 \u23F1\uFE0F ${shortTime(ui.sleepRemainingSec)}" else "\u0645\u0624\u0642\u062A \u0627\u0644\u0646\u0648\u0645 \u23F1\uFE0F"
    }

    private fun showHealth(text: String, done: Int, total: Int) {
        binding.tvHealthProgress.text = text
        binding.pbHealth.isIndeterminate = total == 0
        if (total > 0) { binding.pbHealth.max = total; binding.pbHealth.progress = done }
        binding.healthProgressChip.visibility = View.VISIBLE
    }

    private fun shortTime(sec: Long): String =
        if (sec >= 3600) String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
        else String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60)

    private fun formatTime(totalSec: Long): String {
        val h = totalSec / 3600; val m = (totalSec % 3600) / 60; val s = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }
}
