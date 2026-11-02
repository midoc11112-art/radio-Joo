package com.example.radiojooo.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.Player
import com.example.radiojooo.databinding.ActivityPlayerBinding
import com.example.radiojooo.player.RadioService

class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseButtonUI(isPlaying)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupUIControls()
        extractIntentDataAndPlay()
    }

    override fun onStart() {
        super.onStart()
        attachPlayerListener()
    }

    override fun onStop() {
        super.onStop()
        detachPlayerListener()
    }

    private fun setupToolbar() {
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "شاشة المشغل"
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun setupUIControls() {
        binding.btnPlayPause.setOnClickListener {
            val service = RadioService.getInstance()
            if (service != null) {
                if (service.isPlaying()) {
                    service.pause()
                } else {
                    service.resume()
                }
            } else {
                val streamUrl = intent.getStringExtra("EXTRA_STREAM_URL") ?: ""
                val title = intent.getStringExtra("EXTRA_STATION_TITLE") ?: "إذاعة راديو جو"
                val subtitle = intent.getStringExtra("EXTRA_STATION_SUBTITLE") ?: "بث مباشر"
                if (streamUrl.isNotEmpty()) {
                    startRadioStream(streamUrl, title, subtitle)
                }
            }
        }
    }

    private fun extractIntentDataAndPlay() {
        val streamUrl = intent.getStringExtra("EXTRA_STREAM_URL")
        val title = intent.getStringExtra("EXTRA_STATION_TITLE") ?: "إذاعة راديو جو"
        val subtitle = intent.getStringExtra("EXTRA_STATION_SUBTITLE") ?: ""

        val displayTitle = if (subtitle.isNotEmpty()) "$title ($subtitle)" else title
        binding.tvStationTitle.text = displayTitle

        if (!streamUrl.isNullOrEmpty()) {
            val service = RadioService.getInstance()
            if (service == null || !service.isPlaying()) {
                startRadioStream(streamUrl, title, subtitle)
            }
        }
    }

    private fun startRadioStream(url: String, title: String, subtitle: String) {
        val serviceIntent = Intent(this, RadioService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        binding.root.postDelayed({
            RadioService.getInstance()?.playStation(url, title, subtitle)
            attachPlayerListener()
        }, 200)
    }

    private fun attachPlayerListener() {
        val service = RadioService.getInstance()
        if (service != null) {
            val player = service.getPlayer()
            player?.addListener(playerListener)
            updatePlayPauseButtonUI(service.isPlaying())
        } else {
            updatePlayPauseButtonUI(false)
        }
    }

    private fun detachPlayerListener() {
        RadioService.getInstance()?.getPlayer()?.removeListener(playerListener)
    }

    private fun updatePlayPauseButtonUI(isPlaying: Boolean) {
        val iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val view: Any = binding.btnPlayPause
        if (view is com.google.android.material.button.MaterialButton) {
            view.setIconResource(iconRes)
        } else if (view is android.widget.ImageView) {
            view.setImageResource(iconRes)
        }
    }
}
