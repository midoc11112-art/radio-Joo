package com.example.radiojooo.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.radiojooo.R
import com.example.radiojooo.databinding.ActivityPlayerBinding
import com.example.radiojooo.player.RadioService
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: RadioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        binding.buttonBack.setOnClickListener {
            finish()
        }

        binding.buttonPlayPause.setOnClickListener {
            val intent = Intent(this, RadioService::class.java).apply {
                action = RadioService.ACTION_PLAY_PAUSE
            }
            startService(intent)
        }

        binding.buttonFavorite.setOnClickListener {
            viewModel.currentPlayingStation.value?.let { station ->
                viewModel.toggleFavorite(station)
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.currentPlayingStation.collectLatest { station ->
                    if (station != null) {
                        binding.textViewStationTitle.text = station.name
                        binding.textViewCategory.text = station.category
                        binding.textViewCountry.text = station.countryCode
                        val favIcon = if (station.isFavorite) R.drawable.ic_favorite_filled else R.drawable.ic_favorite_border
                        binding.buttonFavorite.setImageResource(favIcon)
                    } else {
                        finish()
                    }
                }
            }
        }
    }
}
