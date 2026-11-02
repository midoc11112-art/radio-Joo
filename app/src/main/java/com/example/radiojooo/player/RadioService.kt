package com.example.radiojooo.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.TrafficStats
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.CountDownTimer
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.FilteringMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.radiojooo.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

@OptIn(UnstableApi::class)
class RadioService : MediaSessionService() {

    companion object {
        private var instance: RadioService? = null

        private const val CHANNEL_ID = "radio_joo_service_channel"
        private const val NOTIFICATION_ID = 1001

        fun getInstance(): RadioService? = instance

        fun requestPlayStation(
            context: Context,
            title: String,
            uuid: String,
            pUrl: String,
            fUrl: String
        ) {
            val current = instance
            if (current != null) {
                current.playStationInternal(title, uuid, pUrl, fUrl)
            } else {
                val intent = Intent(context, RadioService::class.java).apply {
                    putExtra("ACTION", "PLAY_STATION")
                    putExtra("TITLE", title)
                    putExtra("UUID", uuid)
                    putExtra("PRIMARY_URL", pUrl)
                    putExtra("FALLBACK_URL", fUrl)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }
    }

    private val cloudflareProxyBase = "https://radio-joo-proxy.midoc11112.workers.dev/?url="
    private var isUsingProxy = false
    private lateinit var player: ExoPlayer
    private lateinit var forwardingPlayer: ForwardingPlayer
    private var mediaSession: MediaSession? = null
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var mediaSourceFactory: DefaultMediaSourceFactory
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var isAppInForeground = true
    private var availableUrls = mutableListOf<String>()
    private var currentUrlIndex = 0
    var currentTitle: String = "راديو جو PRO"
    var currentLiveProgram: String = "بث مباشر"
    var currentStationUuid: String = ""
    var isRecording = false
        private set
    private var recordingJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var lastRxBytes = 0L
    private var totalSessionBytes = 0L
    var currentSpeedKbps = 0.0
    var totalDataMb = 0.0
    private var stationStartTimeMs = 0L
    private var sleepTimer: CountDownTimer? = null
    var sleepTimeRemainingMs = 0L
        private set
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsRunnable = object : Runnable {
        override fun run() {
            if (isAppInForeground) {
                calculateDataUsage()
                statsHandler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        createNotificationChannel()
        acquireLocks()
        initializePlayer()
        setupMediaSession()

        statsHandler.post(statsRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            when (it.getStringExtra("ACTION")) {
                "PLAY_STATION" -> {
                    val title = it.getStringExtra("TITLE") ?: "راديو جو PRO"
                    val uuid = it.getStringExtra("UUID") ?: ""
                    val pUrl = it.getStringExtra("PRIMARY_URL") ?: ""
                    val fUrl = it.getStringExtra("FALLBACK_URL") ?: ""
                    playStationInternal(title, uuid, pUrl, fUrl)
                }
            }
        }
        return START_STICKY
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        sleepTimer?.cancel()
        sleepTimer = null
        stopRecording()
        releaseLocks()
        statsHandler.removeCallbacks(statsRunnable)
        try {
            if (::player.isInitialized) player.release()
        } catch (_: Exception) {}
        mediaSession?.release()
        mediaSession = null
        instance = null
        super.onDestroy()
    }

    private fun initializePlayer() {
        val renderersFactory = DefaultRenderersFactory(this).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        }

        trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .build()
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10000, 20000, 1500, 3000)
            .setTargetBufferBytes(1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(0, false)
            .build()

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")

        mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

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
                val currentUrl = availableUrls.getOrNull(currentUrlIndex) ?: ""
                if (!isUsingProxy && currentUrl.isNotBlank()) {
                    startStream(currentUrl, useProxy = true)
                } else {
                    tryNextMirrorServer()
                }
            }
        })
    }

    private fun setupMediaSession() {
        // ForwardingPlayer لاعتراض أوامر النظام
        forwardingPlayer = object : ForwardingPlayer(player) {
            override fun getAvailableCommands(): Player.Commands {
                return super.getAvailableCommands()
                    .buildUpon()
                    .add(Player.COMMAND_STOP)
                    .add(Player.COMMAND_PLAY_PAUSE)
                    .build()
            }

            override fun stop() {
                stopAndExitApp()
            }
        }

        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(contentPendingIntent)
            .build()

        // إعداد مزود الإشعارات
        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setNotificationId(NOTIFICATION_ID)
            .build()

        provider.setSmallIcon(android.R.drawable.ic_media_play)
        setMediaNotificationProvider(provider)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "راديو جو",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "التحكم في بث الراديو المباشر"
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun playStationInternal(title: String, uuid: String, pUrl: String, fUrl: String) {
        stopRecording()
        currentTitle = title
        currentStationUuid = uuid
        currentLiveProgram = "بث مباشر"

        availableUrls.clear()
        if (pUrl.isNotBlank()) availableUrls.add(pUrl.trim())
        if (fUrl.isNotBlank()) {
            availableUrls.addAll(fUrl.split(",").map { it.trim() }.filter { it.isNotBlank() })
        }
        currentUrlIndex = 0
        totalSessionBytes = 0L
        lastRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid())
        stationStartTimeMs = System.currentTimeMillis()

        if (availableUrls.isNotEmpty()) {
            startStream(availableUrls[0], useProxy = true)
        }
    }

    private fun startStream(streamUrl: String, useProxy: Boolean = false) {
        try {
            player.stop()
            player.clearMediaItems()
            isUsingProxy = useProxy

            val finalUrl = if (useProxy) getProxiedUrl(streamUrl) else streamUrl
            val uri = Uri.parse(finalUrl)

            val mimeType = when {
                streamUrl.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                streamUrl.contains(".aac", ignoreCase = true) -> MimeTypes.AUDIO_AAC
                else -> MimeTypes.AUDIO_MPEG
            }

            val metadata = MediaMetadata.Builder()
                .setTitle(currentTitle)
                .setArtist(currentLiveProgram)
                .build()

            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .setMimeType(mimeType)
                .setMediaMetadata(metadata)
                .build()

            val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)
            val filteredSource = FilteringMediaSource(mediaSource, C.TRACK_TYPE_AUDIO)

            player.setMediaSource(filteredSource)
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getProxiedUrl(rawUrl: String): String {
        if (rawUrl.isBlank()) return rawUrl
        if (rawUrl.contains("radio-joo-proxy.midoc11112.workers.dev")) return rawUrl
        return try {
            cloudflareProxyBase + URLEncoder.encode(rawUrl, "UTF-8")
        } catch (e: Exception) {
            cloudflareProxyBase + Uri.encode(rawUrl)
        }
    }

    private fun tryNextMirrorServer() {
        if (availableUrls.isEmpty()) return

        if (availableUrls.size == 1) {
            statsHandler.postDelayed({
                if (availableUrls.isNotEmpty()) startStream(availableUrls[0], useProxy = true)
            }, 4000)
            return
        }

        currentUrlIndex = (currentUrlIndex + 1) % availableUrls.size
        statsHandler.postDelayed({
            if (availableUrls.isNotEmpty()) startStream(availableUrls[currentUrlIndex], useProxy = true)
        }, 1500)
    }

    fun setAppForegroundState(inForeground: Boolean) {
        isAppInForeground = inForeground
        if (inForeground) {
            statsHandler.post(statsRunnable)
        } else {
            statsHandler.removeCallbacks(statsRunnable)
        }
    }

    fun pausePlayback() {
        if (::player.isInitialized) player.pause()
    }

    fun resumePlayback() {
        if (::player.isInitialized) player.play()
    }

    fun isPlaying(): Boolean = ::player.isInitialized && player.isPlaying

    fun closeApp() {
        stopAndExitApp()
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadioJoo::ServiceWakeLock")
            wakeLock?.acquire(10 * 60 * 60 * 1000L)

            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RadioJoo::ServiceWifiLock")
            wifiLock?.acquire()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getRecordingsFolder(): File {
        val folder = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "RadioJoo"
        )
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
        val finalUrl = if (isUsingProxy) getProxiedUrl(currentUrl) else currentUrl

        recordingJob = serviceScope.launch {
            try {
                val dir = getRecordingsFolder()
                val safeTitle = currentTitle.replace(Regex("[^a-zA-Z0-9_ -]"), "_")
                val file = File(dir, "Rec_${safeTitle}_${System.currentTimeMillis()}.mp3")

                val connection = URL(finalUrl).openConnection().apply {
                    connectTimeout = 10000
                    readTimeout = 10000
                    setRequestProperty("User-Agent", "Mozilla/5.0")
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

                MediaScannerConnection.scanFile(
                    applicationContext,
                    arrayOf(file.absolutePath),
                    arrayOf("audio/mpeg"),
                    null
                )
            } catch (e: Exception) {
                e.printStackTrace()
                isRecording = false
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
    }

    fun startSleepTimer(minutes: Int) {
        sleepTimer?.cancel()
        if (minutes <= 0) {
            sleepTimeRemainingMs = 0L
            return
        }

        val totalMs = minutes * 60 * 1000L
        sleepTimer = object : CountDownTimer(totalMs, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                sleepTimeRemainingMs = millisUntilFinished
            }

            override fun onFinish() {
                sleepTimeRemainingMs = 0L
                stopAndExitApp()
            }
        }.start()
    }

    private fun stopAndExitApp() {
        sleepTimer?.cancel()
        sleepTimer = null
        stopRecording()
        statsHandler.removeCallbacks(statsRunnable)

        try {
            if (::player.isInitialized) player.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        releaseLocks()
        stopSelf()
    }

    fun getListeningDurationFormatted(): String {
        if (!::player.isInitialized || !player.isPlaying || stationStartTimeMs == 0L) return "00:00:00"
        val diffSec = (System.currentTimeMillis() - stationStartTimeMs) / 1000
        return String.format(
            Locale.US,
            "%02d:%02d:%02d",
            diffSec / 3600,
            (diffSec % 3600) / 60,
            diffSec % 60
        )
    }

    private fun calculateDataUsage() {
        if (::player.isInitialized && player.isPlaying) {
            val currentRx = TrafficStats.getUidRxBytes(android.os.Process.myUid())
            if (lastRxBytes != 0L && currentRx > lastRxBytes) {
                val bytesThisSecond = currentRx - lastRxBytes
                totalSessionBytes += bytesThisSecond
                currentSpeedKbps = bytesThisSecond / 1024.0
                totalDataMb = totalSessionBytes / (1024.0 * 1024.0)
            }
            lastRxBytes = currentRx
        } else {
            currentSpeedKbps = 0.0
        }
    }
}
