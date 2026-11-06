package com.example.radiojooo.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.media.audiofx.LoudnessEnhancer
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
import android.util.Log
import android.util.LruCache
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.metadata.icy.IcyInfo
import com.example.radiojooo.R
import com.example.radiojooo.data.local.AppDatabase
import com.example.radiojooo.data.repository.RadioRepository
import com.example.radiojooo.ui.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

class RadioService : Service() {

    companion object {
        private var instance: RadioService? = null
        private const val CHANNEL_ID = "radio_joo_service_channel"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "radio_joo_prefs"
        private const val KEY_LAST_GOOD_PREFIX = "last_good_"
        private const val KEY_DEAD_PREFIX = "dead_"
        private const val KEY_HEALED_PREFIX = "healed_url_"
        private const val DEAD_TTL_MS = 30 * 60 * 1000L
        private const val LAST_GOOD_TTL_MS = 60 * 60 * 1000L
        private const val ROOT_BLACKLIST_TTL_MS = 24 * 60 * 60 * 1000L
        private const val MAX_RETRY_SAME_URL = 2
        private const val MAX_HEALING_ATTEMPTS = 3
        private const val STABLE_PLAYBACK_RESET_MS = 30_000L
        private const val TAG = "RadioService"

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

    private val lastGoodUrlCache = LruCache<String, Long>(200)
    private val deadUrlCache = LruCache<String, Long>(200)
    private val urlFailureCount = ConcurrentHashMap<String, Int>()
    private val prefs: SharedPreferences by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    private val sessionCounter = AtomicLong(0)
    @Volatile private var currentSessionId: Long = 0L
    private var activeProbeJob: Job? = null
    private var activeHealingJob: Job? = null
    private var healingAttempts: Int = 0

    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

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
                handlePlaybackError(
                    PlaybackException("Buffer timeout", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
                    currentSessionId
                )
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
        loadCachesFromPrefs()
    }

    private fun loadCachesFromPrefs() {
        try {
            val all = prefs.all
            for ((k, v) in all) {
                if (v !is Long) continue
                if (k.startsWith(KEY_LAST_GOOD_PREFIX)) {
                    if (System.currentTimeMillis() - v < LAST_GOOD_TTL_MS) {
                        lastGoodUrlCache.put(k.removePrefix(KEY_LAST_GOOD_PREFIX), v)
                    }
                } else if (k.startsWith(KEY_DEAD_PREFIX)) {
                    val age = System.currentTimeMillis() - v
                    if (age < DEAD_TTL_MS || age < ROOT_BLACKLIST_TTL_MS) {
                        deadUrlCache.put(k.removePrefix(KEY_DEAD_PREFIX), v)
                    }
                }
            }
        } catch (_: Throwable) {}
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
        activeProbeJob?.cancel(); activeHealingJob?.cancel()
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

        val fastErrorPolicy = object : LoadErrorHandlingPolicy {
            override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
                return if (loadErrorInfo.errorCount >= 1) C.TIME_UNSET else 300L
            }
            override fun getMinimumLoadableRetryCount(dataType: Int): Int = 1
            override fun getFallbackSelectionFor(
                fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
                loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo
            ): LoadErrorHandlingPolicy.FallbackSelection? {
                return null
            }
        }

        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8000).setReadTimeoutMs(8000).setAllowCrossProtocolRedirects(true)
            .setUserAgent("Mozilla/5.0 (Linux; Android 13) ExoPlayer/1.6.1")
            .setDefaultRequestProperties(mapOf("Accept" to "audio/*", "Icy-MetaData" to "0"))

        mediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(fastErrorPolicy)

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
                try {
                    bufferTimeoutHandler.removeCallbacks(bufferTimeoutRunnable)
                    handlePlaybackError(error, currentSessionId)
                } catch (t: Throwable) {}
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                try {
                    updateNotification()
                    if (isPlaying) {
                        acquireLocks()
                        bufferTimeoutHandler.removeCallbacks(bufferTimeoutRunnable)
                        hasPlayedSuccessfully = true
                        val captureSession = currentSessionId
                        val currentUrl = synchronized(availableUrls) { availableUrls.getOrNull(currentUrlIndex) }
                        if (!currentUrl.isNullOrBlank()) {
                            if (currentStationUuid.isNotBlank()) {
                                lastGoodUrlCache.put(currentStationUuid, System.currentTimeMillis())
                                try { prefs.edit().putLong(KEY_LAST_GOOD_PREFIX + currentStationUuid, System.currentTimeMillis()).apply() } catch (_: Throwable) {}
                            }
                            deadUrlCache.remove(currentUrl)
                            urlFailureCount.remove(currentUrl)
                            try { prefs.edit().remove(KEY_DEAD_PREFIX + currentUrl).apply() } catch (_: Throwable) {}
                        }
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
                        serviceScope.launch {
                            try {
                                delay(STABLE_PLAYBACK_RESET_MS)
                                if (currentSessionId == captureSession && isPlaying()) {
                                    retryCount = 0
                                    healingAttempts = 0
                                    urlFailureCount.clear()
                                }
                            } catch (_: Throwable) {}
                        }
                    }
                } catch (t: Throwable) {}
            }

            override fun onPlaybackStateChanged(state: Int) {
                try { if (state == Player.STATE_READY) bufferTimeoutHandler.removeCallbacks(bufferTimeoutRunnable) } catch (t: Throwable) {}
            }
        })
    }

    private fun generateUrlVariants(url: String): List<String> {
        if (url.isBlank()) return emptyList()
        val variants = mutableListOf<String>()
        variants.add(url)
        if (url.startsWith("https://")) {
            variants.add(url.replaceFirst("https://", "http://"))
        } else if (url.startsWith("http://")) {
            variants.add(url.replaceFirst("http://", "https://"))
        }
        if (!url.contains("?")) {
            variants.add(url + "?.mp3")
        }
        if (url.endsWith("/")) {
            variants.add(url.dropLast(1))
        }
        return variants.distinct()
    }

    private fun buildUrlCandidates(primaryUrl: String, fallbackUrl: String): List<String> {
        val candidates = mutableListOf<String>()
        candidates.addAll(generateUrlVariants(primaryUrl))
        if (fallbackUrl.isNotBlank()) {
            fallbackUrl.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { fb ->
                candidates.addAll(generateUrlVariants(fb))
            }
        }
        return candidates.distinct()
    }

    private fun isRootBlacklisted(url: String): Boolean {
        val t = deadUrlCache.get(url) ?: return false
        return System.currentTimeMillis() - t < ROOT_BLACKLIST_TTL_MS && (urlFailureCount[url] ?: 0) >= 3
    }

    private fun recordUrlFailure(url: String) {
        val count = (urlFailureCount[url] ?: 0) + 1
        urlFailureCount[url] = count
        if (count >= 3) {
            deadUrlCache.put(url, System.currentTimeMillis())
            try { prefs.edit().putLong(KEY_DEAD_PREFIX + url, System.currentTimeMillis()).apply() } catch (_: Throwable) {}
        }
    }

    private suspend fun probeHead(url: String): Boolean = suspendCancellableCoroutine { cont ->
        try {
            val req = Request.Builder().url(url).head().build()
            val call = apiClient.newCall(req)
            cont.invokeOnCancellation { try { call.cancel() } catch (_: Throwable) {} }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resume(false) {}
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { r ->
                            var ok = false
                            if (r.isSuccessful || r.code == 206) {
                                val ct = r.header("Content-Type")?.lowercase() ?: ""
                                if (!ct.contains("text/html") && !ct.contains("application/json")) ok = true
                            }
                            if (cont.isActive) cont.resume(ok) {}
                        }
                    } catch (_: Throwable) {
                        if (cont.isActive) cont.resume(false) {}
                    }
                }
            })
        } catch (_: Throwable) {
            if (cont.isActive) cont.resume(false) {}
        }
    }

    private suspend fun preFlightProbe(urls: List<String>, sessionId: Long): String? = withContext(Dispatchers.IO) {
        for (url in urls) {
            if (currentSessionId != sessionId) return@withContext null
            if (isRootBlacklisted(url)) continue
            val deadTime = deadUrlCache.get(url) ?: 0L
            if (deadTime > 0 && System.currentTimeMillis() - deadTime < DEAD_TTL_MS) continue
            val failCount = urlFailureCount[url] ?: 0
            if (failCount >= 3) continue
            try {
                val isAlive = withTimeoutOrNull(1500L) { probeHead(url) } ?: false
                if (currentSessionId != sessionId) return@withContext null
                if (isAlive) {
                    urlFailureCount.remove(url)
                    return@withContext url
                } else {
                    recordUrlFailure(url)
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Throwable) {
                recordUrlFailure(url)
            }
        }
        return@withContext null
    }

    private fun handlePlaybackError(error: PlaybackException, sessionId: Long = currentSessionId) {
        if (currentSessionId != sessionId) return
        if (isHealing.get() || isDestroyed.get()) return

        val currentUrl = synchronized(availableUrls) { availableUrls.getOrNull(currentUrlIndex) } ?: ""
        if (currentUrl.isNotBlank()) {
            recordUrlFailure(currentUrl)
        }

        val errorType = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "403"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "timeout"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "not_found"
            else -> "unknown"
        }

        if (hasPlayedSuccessfully && errorType != "403" && errorType != "not_found") {
            retryCount++
            if (retryCount <= MAX_RETRY_SAME_URL) {
                toast("🔄 إعادة الاتصال ($retryCount/$MAX_RETRY_SAME_URL)...")
                statsHandler.postDelayed({
                    if (currentSessionId == sessionId && !isDestroyed.get()) {
                        val current = synchronized(availableUrls) { availableUrls.getOrNull(currentUrlIndex) } ?: return@postDelayed
                        startStream(current, sessionId, useProxy = true)
                    }
                }, 2000L)
                return
            }
            retryCount = 0
        }

        val urls = synchronized(availableUrls) { availableUrls.toList() }

        if (currentUrlIndex < urls.size - 1) {
            currentUrlIndex++
            toast("🔄 محاولة بديل (${currentUrlIndex + 1}/${urls.size})")
            statsHandler.postDelayed({
                if (currentSessionId == sessionId && !isDestroyed.get()) {
                    startStream(urls[currentUrlIndex], sessionId, useProxy = true)
                }
            }, 500L)
            return
        }

        if (healingAttempts >= MAX_HEALING_ATTEMPTS) {
            toast("❌ المحطة غير متاحة — جرب محطة أخرى")
            return
        }
        if (!isHealing.compareAndSet(false, true)) return
        healingAttempts++

        toast("🧠 Oracle AI يبحث عن بدائل... ($healingAttempts/$MAX_HEALING_ATTEMPTS)")
        val uuidToHeal = currentStationUuid
        val errCap = errorType
        val countryCap = currentCountry
        val catCap = currentCategory

        activeHealingJob = serviceScope.launch {
            try {
                val dao = AppDatabase.getDatabase(applicationContext).stationDao()
                val station = dao.getStationById(uuidToHeal) ?: run { isHealing.set(false); return@launch }
                runCatching { dao.updateHealth(uuidToHeal, false, station.failCount + 1, System.currentTimeMillis()) }
                runCatching { repoInstance.sendFeedback(uuidToHeal, "exo_error", false, errCap, countryCap, catCap) }

                val healResult = withTimeoutOrNull(15000L) { runCatching { repoInstance.neuralHeal(station) }.getOrNull() }
                if (currentSessionId != sessionId) { isHealing.set(false); return@launch }

                if (healResult != null && !isDestroyed.get()) {
                    val newUrl = healResult.url
                    toast("🔍 تم العثور على: ${healResult.matchedName}")
                    Log.d(TAG, "Healed: ${station.name} -> $newUrl")
                    statsHandler.post {
                        try {
                            if (currentSessionId == sessionId && !isDestroyed.get() && ::player.isInitialized) {
                                synchronized(availableUrls) { availableUrls.clear(); availableUrls.add(newUrl) }
                                currentUrlIndex = 0; retryCount = 0; hasPlayedSuccessfully = false
                                startStream(newUrl, sessionId, useProxy = true)
                            }
                        } finally { isHealing.set(false) }
                    }
                } else {
                    if (currentSessionId == sessionId) {
                        toast("❌ لم أجد بديلاً ($healingAttempts/$MAX_HEALING_ATTEMPTS)")
                    }
                    isHealing.set(false)
                }
            } catch (ce: CancellationException) {
                isHealing.set(false)
                throw ce
            } catch (t: Throwable) {
                isHealing.set(false)
            }
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

    // v109.7: 4-layer URL resolution (DB > SharedPrefs > Intent)
    private fun resolveFreshUrls(uuid: String, intentPrimary: String, intentFallback: String): Pair<String, String> {
        var dbPrimary: String? = null
        var dbFallback: String? = null
        try {
            val dao = AppDatabase.getDatabase(applicationContext).stationDao()
            // getStationById is suspend, so we cannot call it here synchronously.
            // We use the backup path instead — SharedPrefs + Intent.
            // The suspend call is done in a coroutine before this function in playStationInternal.
        } catch (_: Throwable) {}

        val healedPrimary = prefs.getString(KEY_HEALED_PREFIX + uuid, null)

        val effectivePrimary = when {
            !dbPrimary.isNullOrBlank() -> dbPrimary!!
            !healedPrimary.isNullOrBlank() -> healedPrimary!!
            intentPrimary.isNotBlank() -> intentPrimary
            else -> ""
        }

        val effectiveFallback = when {
            !dbFallback.isNullOrBlank() -> dbFallback!!
            intentFallback.isNotBlank() -> intentFallback
            else -> ""
        }

        return effectivePrimary to effectiveFallback
    }

    private fun playStationInternal(title: String, uuid: String, pUrl: String, fUrl: String, bitrate: Int = 0, category: String = "", country: String = "") {
        val mySession = sessionCounter.incrementAndGet()
        currentSessionId = mySession

        activeProbeJob?.cancel(); activeProbeJob = null
        activeHealingJob?.cancel(); activeHealingJob = null
        bufferTimeoutHandler.removeCallbacksAndMessages(null)
        statsHandler.removeCallbacksAndMessages(null)

        stopRecording()
        currentTitle = title; currentStationUuid = uuid; currentBitrate = bitrate
        currentCategory = category; currentCountry = country
        _currentTrackTitle.value = ""
        retryCount = 0; healingAttempts = 0; isHealing.set(false); hasPlayedSuccessfully = false

        toast("📻 $title")

        if (isAppInForeground) statsHandler.post(statsRunnable)

        // v109.7: DB query is async — do it in serviceScope first
        serviceScope.launch {
            var dbPrimary: String? = null
            var dbFallback: String? = null
            try {
                val dao = AppDatabase.getDatabase(applicationContext).stationDao()
                val fresh = dao.getStationById(uuid)
                if (fresh != null) {
                    dbPrimary = fresh.primaryUrl.takeIf { it.isNotBlank() }
                    dbFallback = fresh.fallbackUrl.takeIf { it.isNotBlank() }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "DB lookup failed: ${t.message}")
            }
            if (currentSessionId != mySession) return@launch

            // Layer 2: SharedPrefs backup
            val healedPrimary = prefs.getString(KEY_HEALED_PREFIX + uuid, null)

            // Resolve priority: DB > SharedPrefs > Intent
            val effectivePrimary = dbPrimary
                ?: healedPrimary?.takeIf { it.isNotBlank() }
                ?: pUrl
            val effectiveFallback = dbFallback ?: fUrl

            Log.d(TAG, "Resolved URLs for $uuid: primary=$effectivePrimary (db=$dbPrimary, prefs=$healedPrimary, intent=$pUrl)")

            synchronized(availableUrls) {
                availableUrls.clear()
                if (effectivePrimary.isNotBlank()) availableUrls.add(effectivePrimary)
                if (effectiveFallback.isNotBlank()) {
                    availableUrls.addAll(effectiveFallback.split(",").map { it.trim() }.filter { it.isNotBlank() })
                }
            }
            currentUrlIndex = 0
            totalSessionBytes = 0L
            lastRxBytes = TrafficStats.getUidRxBytes(android.os.Process.myUid())
            stationStartTimeMs = System.currentTimeMillis()

            try { if (::player.isInitialized) { player.stop(); player.clearMediaItems() } } catch (_: Throwable) {}

            if (currentSessionId != mySession) return@launch

            activeProbeJob = serviceScope.launch {
                try {
                    val candidates = buildUrlCandidates(effectivePrimary, effectiveFallback)
                    val aliveUrl = preFlightProbe(candidates, mySession)
                    if (currentSessionId != mySession) return@launch
                    if (aliveUrl != null) {
                        val idx = synchronized(availableUrls) { availableUrls.indexOf(aliveUrl) }
                        if (currentSessionId != mySession) return@launch
                        if (idx >= 0) {
                            currentUrlIndex = idx
                        } else {
                            synchronized(availableUrls) { availableUrls.clear(); availableUrls.add(aliveUrl) }
                            currentUrlIndex = 0
                        }
                        statsHandler.post {
                            if (currentSessionId == mySession) startStream(aliveUrl, mySession, useProxy = true)
                        }
                    } else {
                        toast("⚠️ كل الروابط فشلت، بحث فوري...")
                        statsHandler.post {
                            if (currentSessionId == mySession) {
                                handlePlaybackError(
                                    PlaybackException("Pre-flight failed", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED),
                                    mySession
                                )
                            }
                        }
                    }
                } catch (ce: CancellationException) {
                    throw ce
                } catch (t: Throwable) {
                    if (currentSessionId == mySession) {
                        statsHandler.post {
                            if (currentSessionId == mySession) {
                                val first = synchronized(availableUrls) { availableUrls.firstOrNull() }
                                if (first != null) startStream(first, mySession, useProxy = true)
                            }
                        }
                    }
                }
            }
        }

        updateNotification(); acquireLocks()
    }

    private fun startStream(streamUrl: String, sessionId: Long = currentSessionId, useProxy: Boolean = false) {
        if (currentSessionId != sessionId) return
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
        activeProbeJob?.cancel(); activeHealingJob?.cancel()
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