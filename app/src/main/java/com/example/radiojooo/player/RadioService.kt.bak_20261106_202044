package com.example.radiojooo.player

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.media.audiofx.LoudnessEnhancer
import android.net.TrafficStats
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.metadata.icy.IcyInfo
import com.example.radiojooo.R
import com.example.radiojooo.data.local.AppDatabase
import com.example.radiojooo.data.repository.RadioRepository
import com.example.radiojooo.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class RadioService : Service() {

    companion object {
        private var instance: RadioService? = null
        private const val CHANNEL_ID = "radio_joo_service_channel"
        private const val NOTIFICATION_ID = 1001
        fun getInstance(): RadioService? = instance

        fun requestPlayStation(
            context: Context, title: String, uuid: String, pUrl: String, fUrl: String,
            bitrate: Int = 0, category: String = "", country: String = ""
        ) {
            val intent = Intent(context, RadioService::class.java).apply {
                putExtra("ACTION", "PLAY_STATION"); putExtra("TITLE", title); putExtra("UUID", uuid)
                putExtra("PRIMARY_URL", pUrl); putExtra("FALLBACK_URL", fUrl); putExtra("BITRATE", bitrate)
                putExtra("CATEGORY", category); putExtra("COUNTRY", country)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
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
    private val availableUrls = mutableListOf<String>()
    private var currentUrlIndex = 0
    private var loudnessEnhancer: LoudnessEnhancer? = null

    var currentTitle: String = "راديو جو PRO"
    var currentBitrate: Int = 0
    var currentCategory: String = ""
    var currentCountry: String = ""
    var currentStationUuid: String = ""
    var isRecording = false; private set
    private var recordingJob: Job? = null

    private val _currentTrackTitle = MutableStateFlow("")
    val currentTrackTitle: StateFlow<String> = _currentTrackTitle.asStateFlow()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastRxBytes = 0L
    private var totalSessionBytes = 0L
    var currentSpeedKbps = 0.0
    var totalDataMb = 0.0
    private var stationStartTimeMs = 0L
    private var sleepTimer: CountDownTimer? = null
    var sleepTimeRemainingMs = 0L; private set

    private var retryCount = 0
    private val maxRetries = 5
    private val statsHandler = Handler(Looper.getMainLooper())
    private val bufferTimeoutHandler = Handler(Looper.getMainLooper())
    private val statsRunnable = object : Runnable {
        override fun run() {
            if (isAppInForeground) { calculateDataUsage(); statsHandler.postDelayed(this, 1000) }
        }
    }

    private val bufferTimeoutRunnable = Runnable {
        try {
            if (::player.isInitialized && player.playbackState == Player.STATE_BUFFERING && !player.isPlaying && !hasPlayedSuccessfully) {
                toast("⏱️ البث متجمد...")
                handlePlaybackError(PlaybackException("Buffer timeout", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
            }
        } catch (t: Throwable) {}
    }

    private val isHealing = AtomicBoolean(false)
    private val isDestroyed = AtomicBoolean(false)
    private var hasPlayedSuccessfully = false
    private val repoInstance: RadioRepository by lazy { RadioRepository(applicationContext) }

    inner class LocalBinder : Binder() { fun getService(): RadioService = this@RadioService }
    override fun onBind(intent: Intent?): IBinder = binder

    private fun toast(msg: String) {
        try { Handler(Looper.getMainLooper()).post { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() } } catch (t: Throwable) {}
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        acquireLocks()
        initializePlayer()
        statsHandler.post(statsRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try { startForegroundWithNotification() } catch (t: Throwable) {}
        intent?.let {
            when {
                it.action == RadioActionReceiver.ACTION_PLAY_PAUSE -> {
                    if (isPlaying()) pausePlayback() else resumePlayback(); updateNotification()
                }
                it.action == RadioActionReceiver.ACTION_STOP -> closeApp()
                it.getStringExtra("ACTION") == "PLAY_STATION" -> {
                    playStationInternal(
                        it.getStringExtra("TITLE") ?: "راديو جو",
                        it.getStringExtra("UUID") ?: "",
                        it.getStringExtra("PRIMARY_URL") ?: "",
                        it.getStringExtra("FALLBACK_URL") ?: "",
                        it.getIntExtra("BITRATE", 0),
                        it.getStringExtra("CATEGORY") ?: "",
                        it.getStringExtra("COUNTRY") ?: ""
                    )
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isDestroyed.set(true)
        sleepTimer?.cancel(); stopRecording(); releaseLocks()
        statsHandler.removeCallbacksAndMessages(null)
        bufferTimeoutHandler.removeCallbacksAndMessages(null)
        try { loudnessEnhancer?.release() } catch (_: Throwable) {}
        try { if (::player.isInitialized) player.release() } catch (_: Throwable) {}
        try { serviceScope.cancel() } catch (_: Throwable) {}
        instance = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {}

    private fun initializePlayer() {
        val renderersFactory = DefaultRenderersFactory(this).apply { setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF) }

        trackSelector = DefaultTrackSelector(this).apply {
            parameters = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setMaxAudioBitrate(32_000)
                .setForceLowestBitrate(true)
                .setAllowAudioMixedMimeTypeAdaptiveness(false)
                .setAllowVideoMixedMimeTypeAdaptiveness(false)
                .build()
        }

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1000, 2000, 500, 1000)
            .setTargetBufferBytes(-1)
            .build()

        val loadErrorPolicy = object : DefaultLoadErrorHandlingPolicy() {
            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                return minOf(1000L * (loadErrorInfo.errorCount + 1), 5000L)
            }
        }

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(30000).setReadTimeoutMs(30000).setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android 13) ExoPlayer/1.6.1")
            .setDefaultRequestProperties(mapOf("Accept" to "audio/*", "Icy-MetaData" to "0"))

        mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(loadErrorPolicy)

        player = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector).setLoadControl(loadControl).setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(false).build()
        player.setWakeMode(C.WAKE_MODE_NETWORK)

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                try {
                    loudnessEnhancer?.release()
                    if (audioSessionId != C.AUDIO_SESSION_ID_UNSET) {
                        loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                            setTargetGain(600)
                            enabled = true
                        }
                    }
                } catch (t: Throwable) {}
            }

            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val entry = metadata.get(i)
                    if (entry is IcyInfo) {
                        val title = entry.title
                        if (!title.isNullOrBlank()) _currentTrackTitle.value = title
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                try { bufferTimeoutHandler.removeCallbacks(bufferTimeoutRunnable); handlePlaybackError(error) } catch (t: Throwable) {}
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                try {
                    updateNotification()
                    if (isPlaying) {
                        acquireLocks()
                        bufferTimeoutHandler.removeCallbacks(bufferTimeoutRunnable)
                        hasPlayedSuccessfully = true
                        if (currentStationUuid.isNotBlank()) {
                            serviceScope.launch {
                                try {
                                    delay(10000)
                                    if (isPlaying() && currentStationUuid.isNotBlank()) {
                                        repoInstance.sendFeedback(currentStationUuid, "exo_active", true)
                                    }
                                } catch (_: Throwable) {}
                            }
                        }
                    }
                } catch (t: Throwable) {}
            }

            override fun onPlaybackStateChanged(state: Int) {
                try { if (state == Player.STATE_READY) bufferTimeoutHandler.removeCallbacks(bufferTimeoutRunnable) } catch (t: Throwable) {}
            }
        })
    }

    private fun handlePlaybackError(error: PlaybackException) {
        if (isHealing.get() || isDestroyed.get()) return

        val errorType = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "403"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "timeout"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "not_found"
            else -> "unknown"
        }

        if (hasPlayedSuccessfully && errorType != "403" && errorType != "not_found") {
            retryCount++
            if (retryCount <= 5) {
                toast("🔄 إعادة الاتصال...")
                statsHandler.postDelayed({
                    if (!isDestroyed.get()) {
                        val urls = synchronized(availableUrls) { availableUrls.toList() }
                        val currentUrl = urls.getOrNull(currentUrlIndex) ?: return@postDelayed
                        startStream(currentUrl, useProxy = true)
                    }
                }, 3000L)
                return
            } else {
                retryCount = 0
                toast("❌ توقف البث")
                return
            }
        }

        val urls = synchronized(availableUrls) { availableUrls.toList() }

        if (currentUrlIndex < urls.size - 1) {
            currentUrlIndex++
            toast("🔄 محاولة خادم بديل (${currentUrlIndex + 1}/${urls.size})")
            statsHandler.postDelayed({
                if (!isDestroyed.get()) startStream(urls[currentUrlIndex], useProxy = true)
            }, 2000L)
            return
        }

        retryCount++
        if (retryCount <= maxRetries) {
            val delayMs = when (retryCount) { 1 -> 2000L; 2 -> 4000L; else -> 8000L }
            toast("🔄 إعادة محاولة $retryCount/$maxRetries")
            statsHandler.postDelayed({
                if (!isDestroyed.get()) startStream(urls.getOrNull(currentUrlIndex) ?: "", useProxy = true)
            }, delayMs)
            return
        }

        retryCount = 0
        if (currentStationUuid.isBlank() || isDestroyed.get()) return
        if (!isHealing.compareAndSet(false, true)) return

        toast("🧠 Oracle AI يبحث عن بدائل...")
        val uuidToHeal = currentStationUuid
        val errCap = errorType
        val countryCap = currentCountry
        val catCap = currentCategory

        serviceScope.launch {
            try {
                val dao = AppDatabase.getDatabase(applicationContext).stationDao()
                val station = dao.getStationById(uuidToHeal) ?: run { isHealing.set(false); return@launch }
                runCatching { dao.updateHealth(uuidToHeal, false, station.failCount + 1, System.currentTimeMillis()) }
                runCatching { repoInstance.sendFeedback(uuidToHeal, "exo_error", false, errCap, countryCap, catCap) }

                val healResult = withTimeoutOrNull(30000L) { runCatching { repoInstance.neuralHeal(station) }.getOrNull() }

                if (healResult != null && !isDestroyed.get()) {
                    val newUrl = healResult.url
                    toast("🔍 تم العثور على: ${healResult.matchedName}")
                    delay(2000L)

                    val isValidAudio = withContext(Dispatchers.IO) {
                        try {
                            val probe = URL(newUrl).openConnection().apply { connectTimeout = 5000; readTimeout = 5000 }
                            probe.connect()
                            val ct = probe.contentType?.lowercase() ?: ""
                            ct.startsWith("audio/") || ct.contains("mpeg") || ct.contains("octet-stream")
                        } catch (e: Exception) { false }
                    }
                    if (isValidAudio) {
                        statsHandler.post {
                            try {
                                if (!isDestroyed.get() && ::player.isInitialized) {
                                    synchronized(availableUrls) { availableUrls.clear(); availableUrls.add(newUrl) }
                                    currentUrlIndex = 0; retryCount = 0; hasPlayedSuccessfully = false
                                    startStream(newUrl, useProxy = true)
                                }
                            } catch (t: Throwable) {} finally { isHealing.set(false) }
                        }
                    } else {
                        toast("⚠️ الرابط البديل ليس صوتاً")
                        isHealing.set(false)
                        tryFallback()
                    }
                } else {
                    toast("❌ لم أجد بديلاً")
                    isHealing.set(false)
                    tryFallback()
                }
            } catch (t: Throwable) { isHealing.set(false); tryFallback() }
        }
    }

    private fun tryFallback() {
        val urls = synchronized(availableUrls) { availableUrls.toList() }
        if (urls.size > 1) {
            currentUrlIndex = (currentUrlIndex + 1) % urls.size
            toast("🔄 تجربة احتياطي #${currentUrlIndex + 1}")
            statsHandler.post {
                try {
                    if (!isDestroyed.get() && ::player.isInitialized) startStream(urls[currentUrlIndex], useProxy = true)
                } catch (t: Throwable) {}
            }
        } else {
            toast("❌ المحطة غير متاحة حالياً")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "راديو جو", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableLights(false); enableVibration(false); setSound(null, null)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    fun updateNotification() {
        try { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification()) } catch (t: Throwable) {}
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stopPendingIntent = PendingIntent.getBroadcast(this, 1, Intent(RadioActionReceiver.ACTION_STOP).apply { setPackage(packageName) }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val playPausePendingIntent = PendingIntent.getBroadcast(this, 0, Intent(RadioActionReceiver.ACTION_PLAY_PAUSE).apply { setPackage(packageName) }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val isPlaying = try { player.isPlaying } catch (t: Throwable) { false }
        val playPauseIcon = if (isPlaying) R.drawable.ic_notification_pause else R.drawable.ic_notification_play
        val playPauseText = if (isPlaying) "إيقاف" else "تشغيل"
        val subTextParts = mutableListOf<String>()
        if (currentBitrate > 0) subTextParts.add("$currentBitrate Kbps")
        if (currentCategory.isNotBlank()) subTextParts.add(currentCategory)
        if (currentCountry.isNotBlank()) subTextParts.add(currentCountry)
        val trackTitle = _currentTrackTitle.value
        val contentText = if (trackTitle.isNotBlank()) trackTitle else subTextParts.joinToString(" • ").ifBlank { "راديو جو" }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle).setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_play)
            .setLargeIcon(try { BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher) } catch (t: Throwable) { null })
            .setContentIntent(contentIntent).setOngoing(true).setShowWhen(false).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT).setColor(0xFF1976D2.toInt()).setColorized(true)
            .addAction(R.drawable.ic_notification_close, "خروج", stopPendingIntent)
            .addAction(playPauseIcon, playPauseText, playPausePendingIntent)
            .setStyle(MediaStyle().setShowActionsInCompactView(0, 1)).build()
    }

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun playStationInternal(title: String, uuid: String, pUrl: String, fUrl: String, bitrate: Int = 0, category: String = "", country: String = "") {
        stopRecording()
        currentTitle = title; currentStationUuid = uuid; currentBitrate = bitrate
        currentCategory = category; currentCountry = country
        _currentTrackTitle.value = ""
        retryCount = 0; isHealing.set(false); hasPlayedSuccessfully = false
        synchronized(availableUrls) {
            availableUrls.clear()
            if (pUrl.isNotBlank()) availableUrls.add(pUrl)
            if (fUrl.isNotBlank()) availableUrls.addAll(fUrl.split(",").map { it.trim() }.filter { it.isNotBlank() })
        }
        currentUrlIndex = 0; totalSessionBytes = 0L
        lastRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid())
        stationStartTimeMs = System.currentTimeMillis()
        toast("📻 $title")

        val firstUrl = synchronized(availableUrls) { availableUrls.firstOrNull() }
        if (firstUrl != null) startStream(firstUrl, useProxy = true)
        updateNotification(); acquireLocks()
    }

    private fun startStream(streamUrl: String, useProxy: Boolean = false) {
        if (!::player.isInitialized || isDestroyed.get()) return
        try {
            player.stop(); player.clearMediaItems(); isUsingProxy = useProxy
            val finalUrl = if (useProxy) cloudflareProxyBase + URLEncoder.encode(streamUrl, "UTF-8") + "&bitrate=32" else streamUrl
            val mimeType = when {
                streamUrl.contains(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
                streamUrl.contains(".aac", true) -> MimeTypes.AUDIO_AAC
                else -> MimeTypes.AUDIO_MPEG
            }
            val mediaItem = MediaItem.Builder().setUri(Uri.parse(finalUrl)).setMimeType(mimeType)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(currentTitle).build()).build()
            player.setMediaSource(mediaSourceFactory.createMediaSource(mediaItem))
            player.prepare(); player.playWhenReady = true

            bufferTimeoutHandler.removeCallbacks(bufferTimeoutRunnable)
            bufferTimeoutHandler.postDelayed(bufferTimeoutRunnable, 30000L)
        } catch (t: Throwable) { toast("❌ خطأ في التشغيل") }
    }

    fun setAppForegroundState(inForeground: Boolean) { isAppInForeground = inForeground; if (inForeground) statsHandler.post(statsRunnable) else statsHandler.removeCallbacks(statsRunnable) }
    fun pausePlayback() { try { if (::player.isInitialized) player.pause() } catch (t: Throwable) {} }
    fun resumePlayback() { try { if (::player.isInitialized) player.play() } catch (t: Throwable) {} }
    fun isPlaying(): Boolean = try { ::player.isInitialized && player.isPlaying } catch (t: Throwable) { false }
    fun closeApp() { stopAndExitApp() }

    private fun acquireLocks() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (wakeLock == null || wakeLock?.isHeld == false) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RadioJoo::WakeLock")
                wakeLock?.acquire(10 * 60 * 60 * 1000L)
            }
            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            if (wifiLock == null || wifiLock?.isHeld == false) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RadioJoo::WifiLock")
                wifiLock?.acquire()
            }
        } catch (t: Throwable) {}
    }

    private fun releaseLocks() {
        try { if (wakeLock?.isHeld == true) wakeLock?.release() } catch (t: Throwable) {}
        try { if (wifiLock?.isHeld == true) wifiLock?.release() } catch (t: Throwable) {}
    }

    fun getRecordingsFolder(): File {
        val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "RadioJoo")
        if (!folder.exists()) folder.mkdirs()
        return folder
    }

    fun toggleRecording(): Boolean { if (isRecording) stopRecording() else startRecording(); return isRecording }

    private fun startRecording() {
        try {
            val urls = synchronized(availableUrls) { availableUrls.toList() }
            if (urls.isEmpty()) return
            isRecording = true
            val currentUrl = urls.getOrNull(currentUrlIndex) ?: return
            val finalUrl = if (isUsingProxy) cloudflareProxyBase + URLEncoder.encode(currentUrl, "UTF-8") else currentUrl
            recordingJob = serviceScope.launch {
                try {
                    val file = File(getRecordingsFolder(), "Rec_${currentTitle.replace(Regex("[^a-zA-Z0-9_ -]"), "_")}_${System.currentTimeMillis()}.mp3")
                    URL(finalUrl).openConnection().apply { connectTimeout = 10000; readTimeout = 10000 }
                        .getInputStream().use { input ->
                            FileOutputStream(file).use { output ->
                                val buffer = ByteArray(4096); var read = 0
                                while (isRecording && input.read(buffer).also { read = it } != -1) output.write(buffer, 0, read)
                            }
                        }
                    MediaScannerConnection.scanFile(applicationContext, arrayOf(file.absolutePath), arrayOf("audio/mpeg"), null)
                } catch (t: Throwable) { isRecording = false }
            }
        } catch (t: Throwable) { isRecording = false }
    }

    private fun stopRecording() { isRecording = false; recordingJob?.cancel(); recordingJob = null }

    fun startSleepTimer(minutes: Int) {
        sleepTimer?.cancel(); if (minutes <= 0) { sleepTimeRemainingMs = 0L; return }
        sleepTimer = object : CountDownTimer(minutes * 60 * 1000L, 1000) {
            override fun onTick(ms: Long) { sleepTimeRemainingMs = ms }
            override fun onFinish() { sleepTimeRemainingMs = 0L; stopAndExitApp() }
        }.start()
    }

    private fun stopAndExitApp() {
        sleepTimer?.cancel(); stopRecording(); statsHandler.removeCallbacks(statsRunnable)
        bufferTimeoutHandler.removeCallbacksAndMessages(null)
        try { if (::player.isInitialized) player.stop() } catch (t: Throwable) {}
        releaseLocks(); try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (t: Throwable) {}
        stopSelf()
    }

    fun getListeningDurationFormatted(): String {
        if (!::player.isInitialized || !isPlaying() || stationStartTimeMs == 0L) return "00:00:00"
        val sec = (System.currentTimeMillis() - stationStartTimeMs) / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
    }

    private fun calculateDataUsage() {
        try {
            if (::player.isInitialized && player.isPlaying) {
                val rx = TrafficStats.getUidRxBytes(android.os.Process.myUid())
                if (lastRxBytes != 0L && rx > lastRxBytes) {
                    val b = rx - lastRxBytes; totalSessionBytes += b; currentSpeedKbps = b / 1024.0
                    totalDataMb = totalSessionBytes / (1024.0 * 1024.0)
                }
                lastRxBytes = rx
            } else currentSpeedKbps = 0.0
        } catch (t: Throwable) {}
    }
}