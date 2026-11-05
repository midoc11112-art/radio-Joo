package com.example.radiojooo.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import com.example.radiojooo.databinding.ActivityPlayerBinding
import com.example.radiojooo.player.RadioService
import java.util.Locale

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private val viewModel: RadioViewModel by viewModels()

    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updatePlayerUI()
            updateHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    override fun onStart() {
        super.onStart()
        RadioService.getInstance()?.setAppForegroundState(true)
        updateHandler.post(updateRunnable)
    }

    override fun onStop() {
        super.onStop()
        RadioService.getInstance()?.setAppForegroundState(false)
        updateHandler.removeCallbacks(updateRunnable)
    }

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnPlayPause.setOnClickListener {
            val service = RadioService.getInstance() ?: return@setOnClickListener
            if (service.isPlaying()) service.pausePlayback() else service.resumePlayback()
        }

        binding.btnRecord.setOnClickListener {
            val service = RadioService.getInstance() ?: return@setOnClickListener
            val isRec = service.toggleRecording()
            binding.tvRecordingStatus.visibility = if (isRec) View.VISIBLE else View.GONE
        }

        binding.btnOpenFolder.setOnClickListener {
            val service = RadioService.getInstance() ?: return@setOnClickListener
            val folder = service.getRecordingsFolder()
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.fromFile(folder), "*/*")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "مجلد التسجيلات: ${folder.absolutePath}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updatePlayerUI() {
        val service = RadioService.getInstance() ?: return
        val currentStation = viewModel.currentPlayingStation.value

        binding.tvStationTitle.text = currentStation?.name ?: service.currentTitle
        binding.btnPlayPause.text = if (service.isPlaying()) "إيقاف مؤقت ⏸️" else "تشغيل 🔴"

        binding.tvDataUsage.text = String.format(Locale.US, "%.2f MB", service.totalDataMb)
        binding.tvListeningTime.text = service.getListeningDurationFormatted()
        binding.tvConnectionSpeed.text = String.format(Locale.US, "%.1f KB/s", service.currentSpeedKbps)

        binding.tvRecordingStatus.visibility = if (service.isRecording) View.VISIBLE else View.GONE
    }
}
