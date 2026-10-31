package com.example.radiojooo.player

import android.app.PendingIntent
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
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
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

class RadioService : MediaSessionService() {

    private val cloudflareProxyBase = "https://radio-joo-proxy.midoc11112.workers.dev/?url="
    private var isUsingProxy = false

    private fun getProxiedUrl(rawUrl: String): String {
        if (rawUrl.isBlank()) return rawUrl
        if (rawUrl.contains("radio-joo-proxy.midoc11112.workers.dev")) return rawUrl
        return try {
            cloudflareProxyBase + URLEncoder.encode(rawUrl, "UTF-8")
        } catch (e: Exception) {
            cloudflareProxyBase + Uri.encode(rawUrl)
        }
    }

    private lateinit var player: ExoPlayer
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

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == "ACTION_LOCAL_BIND") {
            return binder
        }
        return super.onBind(intent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    fun setAppForegroundState(inForeground: Boolean) {
        isAppInForeground = inForeground
        if (inForeground) {
            statsHandler.post(statsRunnable)
        } else {
            statsHandler.removeCallbacks(statsRunnable)
        }
    }

    // ✅ دالة إضافية للتحكم من الواجهة الرئيسية
    fun pausePlayback() {
        if (::player.isInitialized) player.pause()
    }

    fun resumePlayback() {
        if (::player.isInitialized) player.play()
    }

    override fun onCreate() {
        super.onCreate()
        acquireLocks()

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

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
        setMediaNotificationProvider(notificationProvider)

        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // ✅ استخدام ForwardingPlayer لاعتراض زر الإغلاق (Stop) من شاشة القفل
        val forwardingPlayer = object : ForwardingPlayer(player) {
            override fun stop() {
                stopAndExitApp()
            }
        }

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setSessionActivity(sessionActivity)
            .setCallback(mediaSessionCallback)
            .build()

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

        statsHandler.post(statsRunnable)
    }

    // ✅ إعداد الأزرار المتاحة لشاشة القفل
    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .remove(Player.COMMAND_SEEK_TO_NEXT)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                .remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
                .add(Player.COMMAND_STOP) // إضافة زر الخروج (X)
                .build()

            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(playerCommands)
                .build()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying) {
            stopSelf()
        }
    }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadioJoo::ServiceWakeLock")
            wakeLock?.acquire(10 * 60 * 60 * 1000L)

            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
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

    fun playStation(title: String, uuid: String, pUrl: String, fUrl: String) {
        stopRecording()
        currentTitle = title
        currentStationUuid = uuid
        currentLiveProgram = "بث مباشر"
        availableUrls.clear()
        if (pUrl.isNotBlank()) availableUrls.add(pUrl.trim())
        if (fUrl.isNotBlank()) availableUrls.addAll(fUrl.split(",").map { it.trim() }.filter { it.isNotBlank() })
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
        val finalUrl = if (isUsingProxy) getProxiedUrl(currentUrl) else currentUrl
        recordingJob = serviceScope.launch {
            try {
                val dir = getRecordingsFolder()
                val safeTitle = currentTitle.replace(Regex("[^a-zA-Z0-9_ -]"), "_")
                val file = File(dir, "Rec_${safeTitle}_${System.currentTimeMillis()}.mp3")
                val connection = URL(finalUrl).openConnection().apply {
                    connectTimeout = 10000; readTimeout = 10000
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
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
            if (::player.isInitialized) {
                player.stop()
                player.release()
            }
        } catch (e: Exception) { e.printStackTrace() }
        releaseLocks()
        stopSelf()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    fun getListeningDurationFormatted(): String {
        if (!::player.isInitialized || !player.isPlaying || stationStartTimeMs == 0L) return "00:00:00"
        val diffSec = (System.currentTimeMillis() - stationStartTimeMs) / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", diffSec / 3600, (diffSec % 3600) / 60, diffSec % 60)
    }

    private fun tryNextMirrorServer() {
        if (availableUrls.isEmpty()) return
        if (availableUrls.size == 1) {
            statsHandler.postDelayed({ if (availableUrls.isNotEmpty()) startStream(availableUrls[0], useProxy = true) }, 4000)
            return
        }
        currentUrlIndex = (currentUrlIndex + 1) % availableUrls.size
        statsHandler.postDelayed({ if (availableUrls.isNotEmpty()) startStream(availableUrls[currentUrlIndex], useProxy = true) }, 1500)
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
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
