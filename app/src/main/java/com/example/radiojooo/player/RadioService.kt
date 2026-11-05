package com.example.radiojooo.player

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.TrafficStats
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.example.radiojooo.R
import com.example.radiojooo.data.local.AppDatabase
import com.example.radiojooo.data.repository.RadioRepository
import com.example.radiojooo.ui.MainActivity
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class RadioService : Service() {
    companion object {
        private var instance: RadioService? = null
        private const val CHANNEL_ID = "radio_joo_service_channel"
        private const val NOTIFICATION_ID = 1001

        fun getInstance(): RadioService? = instance
        fun requestPlayStation(context: Context, title: String, uuid: String, pUrl: String, fUrl: String, bitrate: Int = 0, category: String = "", country: String = "") {
            val intent = Intent(context, RadioService::class.java).apply {
                putExtra("ACTION", "PLAY_STATION"); putExtra("TITLE", title); putExtra("UUID", uuid)
                putExtra("PRIMARY_URL", pUrl); putExtra("FALLBACK_URL", fUrl); putExtra("BITRATE", bitrate)
                putExtra("CATEGORY", category); putExtra("COUNTRY", country)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }
    }

    private val binder = LocalBinder()
    private val cloudflareProxyBase = "https://radio-joo-proxy.midoc11112.workers.dev/?url="
    private var isUsingProxy = false
    private lateinit var player: ExoPlayer
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var mediaSourceFactory: DefaultMediaSourceFactory
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var isAppInForeground = true
    private var availableUrls = mutableListOf<String>()
    private var currentUrlIndex = 0
    var currentTitle: String = "راديو جو PRO"; var currentBitrate = 0; var currentCategory = ""; var currentCountry = ""; var currentStationUuid = ""
    var isRecording = false; private set
    private var recordingJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var lastRxBytes = 0L; private var totalSessionBytes = 0L
    var currentSpeedKbps = 0.0; var totalDataMb = 0.0
    private var stationStartTimeMs = 0L
    private var sleepTimer: CountDownTimer? = null
    var sleepTimeRemainingMs = 0L; private set
    private var retryCount = 0
    private val maxRetries = 3
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsRunnable = object : Runnable { override fun run() { if (isAppInForeground) { calculateDataUsage(); statsHandler.postDelayed(this, 1000) } } }

    inner class LocalBinder : Binder() { fun getService(): RadioService = this@RadioService }
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate(); instance = this; createNotificationChannel(); acquireLocks(); initializePlayer(); statsHandler.post(statsRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        intent?.let {
            when {
                it.action == RadioActionReceiver.ACTION_PLAY_PAUSE -> { if (isPlaying()) pausePlayback() else resumePlayback(); updateNotification() }
                it.action == RadioActionReceiver.ACTION_STOP -> closeApp()
                it.getStringExtra("ACTION") == "PLAY_STATION" -> {
                    playStationInternal(it.getStringExtra("TITLE") ?: "راديو جو", it.getStringExtra("UUID") ?: "", it.getStringExtra("PRIMARY_URL") ?: "", it.getStringExtra("FALLBACK_URL") ?: "", it.getIntExtra("BITRATE", 0), it.getStringExtra("CATEGORY") ?: "", it.getStringExtra("COUNTRY") ?: "")
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        sleepTimer?.cancel(); stopRecording(); releaseLocks(); statsHandler.removeCallbacks(statsRunnable)
        try { if (::player.isInitialized) player.release() } catch (_: Exception) {}
        instance = null; super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {}

    private fun initializePlayer() {
        val renderersFactory = DefaultRenderersFactory(this).setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        trackSelector = DefaultTrackSelector(this).apply { parameters = buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).setMaxAudioBitrate(48000).build() }
        val loadControl = DefaultLoadControl.Builder().setBufferDurationsMs(15000, 60000, 2500, 5000).setTargetBufferBytes(-1).build()
        val dataSourceFactory = DefaultHttpDataSource.Factory().setConnectTimeoutMs(30000).setReadTimeoutMs(30000).setAllowCrossProtocolRedirects(true).setUserAgent("Mozilla/5.0").setDefaultRequestProperties(mapOf("Accept" to "audio/*", "Icy-MetaData" to "0"))
        mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSourceFactory)
        
        player = ExoPlayer.Builder(this, renderersFactory).setTrackSelector(trackSelector).setLoadControl(loadControl).setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build(), true).setHandleAudioBecomingNoisy(false).build()
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                handlePlaybackError(error)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification()
                if (isPlaying) {
                    acquireLocks()
                    // 🚀 إرسال تغذية راجعة للنجاح بعد 10 ثوانٍ (للتأكد من الاستقرار)
                    if (currentStationUuid.isNotBlank()) {
                        serviceScope.launch {
                            delay(10000)
                            if (isPlaying()) {
                                val repo = RadioRepository(applicationContext)
                                repo.sendFeedback(currentStationUuid, availableUrls.getOrNull(currentUrlIndex) ?: "", true)
                            }
                        }
                    }
                }
            }
        })
    }

    private fun handlePlaybackError(error: PlaybackException) {
        retryCount++
        val errorCode = error.errorCode
        val errorString = when (errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "403"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "timeout"
            else -> "unknown"
        }
        
        // إرسال تغذية راجعة للفشل (ليتعلم السيرفر)
        if (currentStationUuid.isNotBlank() && retryCount >= maxRetries) {
            serviceScope.launch {
                val repo = RadioRepository(applicationContext)
                repo.sendFeedback(currentStationUuid, availableUrls.getOrNull(currentUrlIndex) ?: "", false, errorString)
            }
        }

        if (retryCount <= maxRetries) {
            statsHandler.postDelayed({ if (availableUrls.isNotEmpty()) startStream(availableUrls.getOrNull(currentUrlIndex) ?: availableUrls[0], useProxy = true) }, 3000L)
        } else {
            retryCount = 0
            if (currentStationUuid.isNotBlank()) {
                serviceScope.launch {
                    try {
                        val dao = AppDatabase.getDatabase(applicationContext).stationDao()
                        val station = dao.getStationById(currentStationUuid)
                        if (station != null) {
                            dao.updateHealth(currentStationUuid, false, station.failCount + 1, System.currentTimeMillis())
                            
                            // 🚀 محاولة تعافي ذكية (Neural Heal)
                            val repo = RadioRepository(applicationContext)
                            val newUrl = repo.neuralHeal(station)
                            if (newUrl != null) {
                                availableUrls.clear(); availableUrls.add(newUrl); currentUrlIndex = 0
                                statsHandler.post { startStream(newUrl, useProxy = true) }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "راديو جو", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC; enableLights(false); enableVibration(false); setSound(null, null) }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    fun updateNotification() { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification()) }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopIntent = PendingIntent.getBroadcast(this, 1, Intent(RadioActionReceiver.ACTION_STOP).apply { setPackage(packageName) }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val playPauseIntent = PendingIntent.getBroadcast(this, 0, Intent(RadioActionReceiver.ACTION_PLAY_PAUSE).apply { setPackage(packageName) }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val playPauseIcon = if (player.isPlaying) R.drawable.ic_notification_pause else R.drawable.ic_notification_play
        val playPauseText = if (player.isPlaying) "إيقاف" else "تشغيل"
        val subTextParts = mutableListOf<String>()
        if (currentBitrate > 0) subTextParts.add("$currentBitrate Kbps")
        if (currentCategory.isNotBlank()) subTextParts.add(currentCategory)
        if (currentCountry.isNotBlank()) subTextParts.add(currentCountry)
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle).setContentText(subTextParts.joinToString(" • ")).setSmallIcon(R.drawable.ic_notification_play)
            .setLargeIcon(try { BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher) } catch (e: Exception) { null })
            .setContentIntent(contentIntent).setOngoing(true).setShowWhen(false).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setPriority(NotificationCompat.PRIORITY_LOW).setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setColor(0xFF1976D2.toInt()).setColorized(true)
            .addAction(R.drawable.ic_notification_close, "خروج", stopIntent).addAction(playPauseIcon, playPauseText, playPauseIntent)
            .setStyle(MediaStyle().setShowActionsInCompactView(0, 1)).build()
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun playStationInternal(title: String, uuid: String, pUrl: String, fUrl: String, bitrate: Int = 0, category: String = "", country: String = "") {
        stopRecording(); currentTitle = title; currentStationUuid = uuid; currentBitrate = bitrate; currentCategory = category; currentCountry = country
        retryCount = 0; availableUrls.clear()
        if (pUrl.isNotBlank()) availableUrls.add(pUrl)
        if (fUrl.isNotBlank()) availableUrls.addAll(fUrl.split(",").map { it.trim() }.filter { it.isNotBlank() })
        currentUrlIndex = 0; totalSessionBytes = 0L; lastRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid()); stationStartTimeMs = System.currentTimeMillis()
        if (availableUrls.isNotEmpty()) startStream(availableUrls[0], useProxy = true)
        updateNotification(); acquireLocks()
    }

    private fun startStream(streamUrl: String, useProxy: Boolean = false) {
        try {
            player.stop(); player.clearMediaItems(); isUsingProxy = useProxy
            val finalUrl = if (useProxy) cloudflareProxyBase + URLEncoder.encode(streamUrl, "UTF-8") + "&bitrate=48" else streamUrl
            val mimeType = when { streamUrl.contains(".m3u8", true) -> MimeTypes.APPLICATION_M3U8; streamUrl.contains(".aac", true) -> MimeTypes.AUDIO_AAC; else -> MimeTypes.AUDIO_MPEG }
            val mediaItem = MediaItem.Builder().setUri(Uri.parse(finalUrl)).setMimeType(mimeType).setMediaMetadata(MediaMetadata.Builder().setTitle(currentTitle).build()).build()
            player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
            player.prepare(); player.playWhenReady = true
        } catch (e: Exception) { handlePlaybackError(PlaybackException("Stream error", e, PlaybackException.ERROR_CODE_UNSPECIFIED)) }
    }

    fun setAppForegroundState(inForeground: Boolean) { isAppInForeground = inForeground; if (inForeground) statsHandler.post(statsRunnable) else statsHandler.removeCallbacks(statsRunnable) }
    fun pausePlayback() { if (::player.isInitialized) player.pause() }
    fun resumePlayback() { if (::player.isInitialized) player.play() }
    fun isPlaying(): Boolean = ::player.isInitialized && player.isPlaying
    fun closeApp() { stopAndExitApp() }

    private fun acquireLocks() { /* نفس الكود السابق */ }
    private fun releaseLocks() { /* نفس الكود السابق */ }
    fun getRecordingsFolder(): File { /* نفس الكود السابق */ return File("") }
    fun toggleRecording(): Boolean { /* نفس الكود السابق */ return false }
    private fun startRecording() { /* نفس الكود السابق */ }
    private fun stopRecording() { /* نفس الكود السابق */ }
    fun startSleepTimer(minutes: Int) { /* نفس الكود السابق */ }
    private fun stopAndExitApp() { /* نفس الكود السابق */ }
    fun getListeningDurationFormatted(): String { /* نفس الكود السابق */ return "" }
    private fun calculateDataUsage() { /* نفس الكود السابق */ }
}