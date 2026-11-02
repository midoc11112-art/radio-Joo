package com.example.radiojooo.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
        if (!isGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(
                this,
                "إذن الإشعارات مطلوب للتحكم بالراديو من شريط الإشعارات",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        observeViewModel()
        checkNotificationPermission()
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
        viewModel.stations.observe(this) { stations ->
            stationAdapter.submitList(stations)
        }
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
                url = station.primaryUrl,
                title = station.name,
                stationSubtitle = station.category
            )
        }, 200)

        val playerIntent = Intent(this, PlayerActivity::class.java).apply {
            putExtra("EXTRA_STREAM_URL", station.primaryUrl)
            putExtra("EXTRA_STATION_TITLE", station.name)
            putExtra("EXTRA_STATION_SUBTITLE", station.category)
        }
        startActivity(playerIntent)
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
