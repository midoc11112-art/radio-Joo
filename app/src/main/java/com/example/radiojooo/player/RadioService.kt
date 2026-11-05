package com.example.radiojooo.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.radiojooo.R
import com.example.radiojooo.data.model.StationEntity
import com.example.radiojooo.data.repository.RadioRepository
import com.example.radiojooo.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.URLEncoder

class RadioService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var repository: RadioRepository
    private lateinit var exoPlayer: ExoPlayer

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private val _currentStation = MutableStateFlow<StationEntity?>(null)
    val currentStation: StateFlow<StationEntity?> = _currentStation.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var isUsingFallback = false

    inner class LocalBinder : Binder() {
        fun getService(): RadioService = this@RadioService
    }

    override fun onCreate() {
        super.onCreate()
        repository = RadioRepository(applicationContext)
        initLocks()
        initPlayer()
        createNotificationChannel()
    }

    private fun initLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadioJoo::WakeLock")
        wakeLock?.setReferenceCounted(false)

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RadioJoo::WifiLock")
        wifiLock?.setReferenceCounted(false)
    }

    private fun acquireLocks() {
        if (wakeLock?.isHeld == false) wakeLock?.acquire(10 * 60 * 1000L)
        if (wifiLock?.isHeld == false) wifiLock?.acquire()
    }

    private fun releaseLocks() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
    }

    private fun initPlayer() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                _isPlaying.value = isPlayingNow
                if (isPlayingNow) {
                    acquireLocks()
                } else {
                    releaseLocks()
                }
                updateNotification()
            }

            override fun onPlayerError(error: PlaybackException) {
                val current = _currentStation.value
                if (current != null && !isUsingFallback && !current.fallbackUrl.isNull_Empty()) {
                    isUsingFallback = true
                    playUrl(current.fallbackUrl!!)
                } else {
                    _isPlaying.value = false
                    releaseLocks()
                    updateNotification()
                }
            }
        })
    }

    fun playStation(station: StationEntity) {
        _currentStation.value = station
        isUsingFallback = false
        val targetUrl = getProxyUrl(station.primaryUrl)
        playUrl(targetUrl)
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun playUrl(url: String) {
        val mediaItem = MediaItem.fromUri(getProxyUrl(url))
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            if (exoPlayer.playbackState == Player.STATE_ENDED) {
                exoPlayer.seekTo(0)
            }
            exoPlayer.play()
        }
        updateNotification()
    }

    fun stopPlayer() {
        exoPlayer.stop()
        _isPlaying.value = false
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun getProxyUrl(rawUrl: String): String {
        if (rawUrl.startsWith("https://radio-joo-proxy.midoc11112.workers.dev")) return rawUrl
        return try {
            "https://radio-joo-proxy.midoc11112.workers.dev/?url=" + URLEncoder.encode(rawUrl, "UTF-8")
        } catch (e: Exception) {
            rawUrl
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Radio Joo Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "تشغيل إذاعات تطبيق Radio Joo"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val station = _currentStation.value
        val title = station?.name ?: "Radio Joo"
        val content = station?.category ?: "بث مباشر"

        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = if (_isPlaying.value) {
            NotificationCompat.Action(
                R.drawable.ic_notification_pause, "إيقاف",
                getPendingIntent(ACTION_PLAY_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                R.drawable.ic_notification_play, "تشغيل",
                getPendingIntent(ACTION_PLAY_PAUSE)
            )
        }

        val stopAction = NotificationCompat.Action(
            R.drawable.ic_notification_close, "إغلاق",
            getPendingIntent(ACTION_STOP)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_radio_joo)
            .setContentIntent(pendingIntent)
            .addAction(playPauseAction)
            .addAction(stopAction)
            .setStyle(MediaStyle().setShowActionsInCompactView(0, 1))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(_isPlaying.value)
            .build()
    }

    private fun updateNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun getPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, RadioActionReceiver::class.java).apply {
            this.action = action
            setPackage(packageName)
        }
        return PendingIntent.getBroadcast(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.action?.let { action ->
            when (action) {
                ACTION_PLAY_PAUSE -> togglePlayPause()
                ACTION_STOP -> stopPlayer()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        releaseLocks()
        exoPlayer.release()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun String?.isNull_Empty(): Boolean = this == null || this.trim().isEmpty()

    companion object {
        const val CHANNEL_ID = "radio_joo_playback_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY_PAUSE = "com.example.radiojooo.ACTION_PLAY_PAUSE"
        const val ACTION_STOP = "com.example.radiojooo.ACTION_STOP"
    }
}
