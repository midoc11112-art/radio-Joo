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

    // مراقب حالة ExoPlayer لتحديث الأزرار والنصوص في الواجهة التفصيلية تلقائياً
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayPauseButtonUI(isPlaying)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            updatePlaybackStatusUI(playbackState)
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
        // زر التشغيل والإيقاف المؤقت
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

        // زر الإيقاف التام وخروج الخدمة
        binding.btnStop.setOnClickListener {
            RadioService.getInstance()?.stop()
            updatePlayPauseButtonUI(false)
            binding.tvStatus.text = "متوقف"
        }
    }

    private fun extractIntentDataAndPlay() {
        val streamUrl = intent.getStringExtra("EXTRA_STREAM_URL")
        val title = intent.getStringExtra("EXTRA_STATION_TITLE") ?: "إذاعة راديو جو"
        val subtitle = intent.getStringExtra("EXTRA_STATION_SUBTITLE") ?: "بث مباشر"

        binding.tvStationTitle.text = title
        binding.tvStationSubtitle.text = subtitle

        if (!streamUrl.isNullOrEmpty()) {
            val service = RadioService.getInstance()
            // تشغيل المحطة إذا لم تكن الخدمة تعمل أو عند تحديد محطة جديدة
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
            player?.playbackState?.let { updatePlaybackStatusUI(it) }
        } else {
            updatePlayPauseButtonUI(false)
            binding.tvStatus.text = "متوقف"
        }
    }

    private fun detachPlayerListener() {
        RadioService.getInstance()?.getPlayer()?.removeListener(playerListener)
    }

    private fun updatePlayPauseButtonUI(isPlaying: Boolean) {
        if (isPlaying) {
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        } else {
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private fun updatePlaybackStatusUI(playbackState: Int) {
        val isPlaying = RadioService.getInstance()?.isPlaying() == true
        when (playbackState) {
            Player.STATE_BUFFERING -> {
                binding.tvStatus.text = "جاري التحميل..."
            }
            Player.STATE_READY -> {
                binding.tvStatus.text = if (isPlaying) "جاري التشغيل" else "متوقف مؤقتاً"
            }
            Player.STATE_ENDED -> {
                binding.tvStatus.text = "انتهى البث"
            }
            Player.STATE_IDLE -> {
                binding.tvStatus.text = "متوقف"
            }
        }
    }
}
