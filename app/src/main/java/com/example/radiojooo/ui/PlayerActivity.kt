package com.example.radiojooo.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
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

    private var radioService: RadioService? = null
    private var isBound = false

    private val updateHandler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updatePlayerUI()
            updateHandler.postDelayed(this, 1000)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? RadioService.LocalBinder
            radioService = binder?.getService()
            isBound = true
            radioService?.setAppForegroundState(true)
            updatePlayerUI()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            radioService = null
            isBound = false
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

    private fun setupListeners() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnPlayPause.setOnClickListener {
            radioService?.let { service ->
                val intent = Intent(this, RadioService::class.java).apply {
                    action = if (service.isPlaying()) "ACTION_PAUSE" else "ACTION_PLAY"
                }
                startService(intent)
            }
        }

        binding.btnRecord.setOnClickListener {
            radioService?.let { service ->
                val isRec = service.toggleRecording()
                binding.tvRecordingStatus.visibility = if (isRec) View.VISIBLE else View.GONE
            }
        }

        binding.btnOpenFolder.setOnClickListener {
            radioService?.let { service ->
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
    }

    private fun updatePlayerUI() {
        val service = radioService ?: return
        val currentStation = viewModel.currentPlayingStation.value

        binding.tvStationTitle.text = currentStation?.name ?: service.currentTitle
        binding.btnPlayPause.text = if (service.isPlaying()) "إيقاف مؤقت ⏸️" else "تشغيل 🔴"

        binding.tvDataUsage.text = String.format(Locale.US, "%.2f MB", service.totalDataMb)
        binding.tvListeningTime.text = service.getListeningDurationFormatted()
        binding.tvConnectionSpeed.text = String.format(Locale.US, "%.1f KB/s", service.currentSpeedKbps)

        binding.tvRecordingStatus.visibility = if (service.isRecording) View.VISIBLE else View.GONE
    }
}
