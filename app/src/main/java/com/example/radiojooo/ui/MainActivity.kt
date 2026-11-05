package com.example.radiojooo.ui

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.radiojooo.R
import com.example.radiojooo.data.model.StationEntity
import com.example.radiojooo.databinding.ActivityMainBinding
import com.example.radiojooo.player.RadioService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: RadioViewModel by viewModels()
    private lateinit var stationAdapter: StationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupSearchAndFilters()
        setupSwipeRefresh()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        stationAdapter = StationAdapter(
            onItemClick = { station ->
                viewModel.setCurrentStation(station)
                val intent = Intent(this, RadioService::class.java).apply {
                    action = RadioService.ACTION_PLAY_PAUSE
                }
                startService(intent)
                val playerIntent = Intent(this, PlayerActivity::class.java)
                startActivity(playerIntent)
            },
            onFavoriteClick = { station ->
                viewModel.toggleFavorite(station)
            }
        )

        binding.recyclerViewStations.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = stationAdapter
        }
    }

    private fun setupSearchAndFilters() {
        binding.editTextSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                viewModel.setSearchQuery(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.chipGroupCategories.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) {
                viewModel.setCategory("ALL")
                return@setOnCheckedStateChangeListener
            }
            val category = when (checkedIds.first()) {
                R.id.chipQuran -> "QURAN"
                R.id.chipNews -> "NEWS"
                R.id.chipEgypt -> "EGYPT"
                R.id.chipSports -> "SPORTS"
                R.id.chipFavorites -> "FAVORITES"
                else -> "ALL"
            }
            viewModel.setCategory(category)
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
                launch {
                    viewModel.stations.collectLatest { list ->
                        stationAdapter.submitList(list)
                        binding.textViewEmptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }

                launch {
                    viewModel.isRefreshing.collectLatest { refreshing ->
                        binding.swipeRefreshLayout.isRefreshing = refreshing
                    }
                }

                launch {
                    viewModel.refreshResult.collectLatest { success ->
                        val msg = if (success) "تم تحديث المحطات بنجاح" else "تعذر تحديث المحطات من السيرفر"
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    }
                }

                launch {
                    viewModel.currentPlayingStation.collectLatest { station ->
                        if (station != null) {
                            binding.layoutMiniPlayer.visibility = View.VISIBLE
                            binding.textViewMiniPlayerName.text = station.name
                        } else {
                            binding.layoutMiniPlayer.visibility = View.GONE
                        }
                    }
                }
            }
        }
    }
}
