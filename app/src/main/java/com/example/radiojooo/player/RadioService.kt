package com.example.radiojooo.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.TrafficStats
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.example.radiojooo.R
import com.example.radiojooo.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Locale

class RadioService : Service() {

    private lateinit var player: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var isAppInForeground = true
    private var availableUrls = mutableListOf<String>()
    private var currentUrlIndex = 0
    var currentTitle: String = "راديو جو PRO"
    var currentLiveProgram: String = "بث مباشر"

    var isRecording = false; private set
    private var recordingJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    private var lastRxBytes = 0L
    private var totalSessionBytes = 0L
    var currentSpeedKbps = 0.0
    var totalDataMb = 0.0
    private var stationStartTimeMs = 0L

    private var sleepTimer: CountDownTimer? = null
    var sleepTimeRemainingMs = 0L; private set

    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsRunnable = object : Runnable {
        override fun run() {
            if (isAppInForeground) {
                calculateDataUsage()
                statsHandler.postDelayed(this, 1000)
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): RadioService = this@RadioService
    }

    private val binder = LocalBinder()

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    fun setAppForegroundState(inForeground: Boolean) {
        isAppInForeground = inForeground
        if (inForeground) {
            statsHandler.post(statsRunnable)
        } else {
            statsHandler.removeCallbacks(statsRunnable)
        }
    }

    override fun onCreate() {
        super.onCreate()
        acquireLocks()
        createNotificationChannel()

        // 1. حظر معالجات الفيديو كلياً على مستوى المحرك لمنع استهلاك الباقة في محطات الفيديو
        val renderersFactory = DefaultRenderersFactory(this).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        }

        trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setMaxAudioBitrate(48000) // أقصى جودة صوتية لتوفير الباقة
                .build()
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10000, 30000, 1000, 2500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(0, false)
            .build()

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(10000)
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(
                mapOf(
                    "User-Agent" to "RadioJooApp/3.0",
                    "Icy-MetaData" to "0",
                    "Accept-Encoding" to "gzip"
                )
            )

        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)

        player = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.setWakeMode(C.WAKE_MODE_NETWORK)

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                tryNextMirrorServer()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification()
            }
        })

        statsHandler.post(statsRunnable)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // حماية الخدمة من الإغلاق عند مسح التطبيق من قائمة الشاشة
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadioJoo::ServiceWakeLock")
            wakeLock?.acquire(10 * 60 * 60 * 1000L)

            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RadioJoo::ServiceWifiLock")
            wifiLock?.acquire()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "radio_joo_service_channel",
                "تشغيل راديو جو",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "التحكم في بث الراديو المباشر"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // 1. زر التشغيل / الإيقاف المؤقت
        val playPauseIntent = Intent(this, RadioService::class.java).apply {
            action = if (isPlaying()) "ACTION_PAUSE" else "ACTION_PLAY"
        }
        val playPausePending = PendingIntent.getService(
            this, 1, playPauseIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playPauseIcon = if (isPlaying()) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseTitle = if (isPlaying()) "إيقاف مؤقت" else "تشغيل"

        // 2. زر الخروج X
        val closeIntent = Intent(this, RadioService::class.java).apply { action = "ACTION_CLOSE" }
        val closePending = PendingIntent.getService(
            this, 2, closeIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val builder = NotificationCompat.Builder(this, "radio_joo_service_channel")
            .setContentTitle(currentTitle)
            .setContentText(currentLiveProgram)
            .setSmallIcon(R.drawable.ic_app_logo)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(playPauseIcon, playPauseTitle, playPausePending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "خروج X", closePending)

        return builder.build()
    }

    private fun updateNotification() {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(1001, buildNotification())
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "ACTION_PAUSE" -> {
                player.pause()
                updateNotification()
            }
            "ACTION_PLAY" -> {
                player.play()
                updateNotification()
            }
            "ACTION_CLOSE" -> {
                stopAndExitApp()
            }
        }
        return START_STICKY
    }

    fun playStation(title: String, uuid: String, pUrl: String, fUrl: String) {
        stopRecording()
        currentTitle = title
        currentLiveProgram = "بث مباشر"
        availableUrls.clear()
        if (pUrl.isNotBlank()) availableUrls.add(pUrl)
        if (fUrl.isNotBlank()) availableUrls.addAll(fUrl.split(",").filter { it.isNotBlank() })
        currentUrlIndex = 0
        totalSessionBytes = 0L
        lastRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid())
        stationStartTimeMs = System.currentTimeMillis()

        if (availableUrls.isNotEmpty()) {
            startStream(availableUrls[0])
        }

        try {
            startForeground(1001, buildNotification())
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun startStream(streamUrl: String) {
        player.stop()
        player.clearMediaItems()
        val uri = Uri.parse(streamUrl)
        val metadata = MediaMetadata.Builder().setTitle(currentTitle).setArtist(currentLiveProgram).build()
        val mediaItem = MediaItem.Builder().setUri(uri).setMediaMetadata(metadata).build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
    }

    fun isPlaying(): Boolean = ::player.isInitialized && player.isPlaying

    fun getRecordingsFolder(): File {
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "RadioJoo")
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    fun toggleRecording(): Boolean {
        if (isRecording) stopRecording() else startRecording()
        return isRecording
    }

    private fun startRecording() {
        if (availableUrls.isEmpty()) return
        isRecording = true
        val currentUrl = availableUrls.getOrNull(currentUrlIndex) ?: return
        recordingJob = serviceScope.launch {
            try {
                val dir = getRecordingsFolder()
                val safeTitle = currentTitle.replace(Regex("[^a-zA-Z0-9_ -]"), "_")
                val file = File(dir, "Rec_${safeTitle}_${System.currentTimeMillis()}.mp3")
                val connection = URL(currentUrl).openConnection().apply {
                    connectTimeout = 6000; readTimeout = 6000; setRequestProperty("User-Agent", "RadioJooApp/3.0")
                }
                connection.getInputStream().use { input ->
                    FileOutputStream(file).use { output ->
                        val buffer = ByteArray(4096)
                        var bytesRead = 0
                        while (isRecording && input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                        }
                    }
                }
                MediaScannerConnection.scanFile(applicationContext, arrayOf(file.absolutePath), arrayOf("audio/mpeg"), null)
            } catch (e: Exception) { e.printStackTrace(); isRecording = false }
        }
    }

    private fun stopRecording() { isRecording = false; recordingJob?.cancel(); recordingJob = null }

    fun startSleepTimer(minutes: Int) {
        sleepTimer?.cancel()
        if (minutes <= 0) { sleepTimeRemainingMs = 0L; return }
        val totalMs = minutes * 60 * 1000L
        sleepTimer = object : CountDownTimer(totalMs, 1000) {
            override fun onTick(millisUntilFinished: Long) { sleepTimeRemainingMs = millisUntilFinished }
            override fun onFinish() { sleepTimeRemainingMs = 0L; stopAndExitApp() }
        }.start()
    }

    private fun stopAndExitApp() {
        sleepTimer?.cancel()
        stopRecording()
        try {
            player.stop()
            player.release()
        } catch (e: Exception) { e.printStackTrace() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else { @Suppress("DEPRECATION") stopForeground(true) }
        releaseLocks()
        stopSelf()
    }

    fun getListeningDurationFormatted(): String {
        if (!::player.isInitialized || !player.isPlaying || stationStartTimeMs == 0L) return "00:00:00"
        val diffSec = (System.currentTimeMillis() - stationStartTimeMs) / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", diffSec / 3600, (diffSec % 3600) / 60, diffSec % 60)
    }

    private fun tryNextMirrorServer() {
        if (availableUrls.isEmpty()) return
        if (availableUrls.size == 1) {
            statsHandler.postDelayed({ if (availableUrls.isNotEmpty()) startStream(availableUrls[0]) }, 5000)
            return
        }
        currentUrlIndex = (currentUrlIndex + 1) % availableUrls.size
        statsHandler.postDelayed({ if (availableUrls.isNotEmpty()) startStream(availableUrls[currentUrlIndex]) }, 2000)
    }

    private fun calculateDataUsage() {
        if (::player.isInitialized && player.isPlaying) {
            val currentRx = TrafficStats.getUidRxBytes(android.os.Process.myUid())
            if (lastRxBytes != 0L && currentRx > lastRxBytes) {
                val bytesThisSecond = currentRx - lastRxBytes
                totalSessionBytes += bytesThisSecond
                currentSpeedKbps = (bytesThisSecond / 1024.0)
                totalDataMb = (totalSessionBytes / (1024.0 * 1024.0))
            }
            lastRxBytes = currentRx
        } else { currentSpeedKbps = 0.0 }
    }

    override fun onDestroy() {
        sleepTimer?.cancel()
        stopRecording()
        releaseLocks()
        statsHandler.removeCallbacks(statsRunnable)
        try {
            player.release()
        } catch (e: Exception) { e.printStackTrace() }
        super.onDestroy()
    }
}
