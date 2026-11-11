package com.example.radiojooo.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.example.radiojooo.Graph
import com.example.radiojooo.R
import com.example.radiojooo.data.Api
import com.example.radiojooo.data.Server
import com.example.radiojooo.data.Station
import com.example.radiojooo.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicLong

/**
 * The playback engine.
 *  - Data saver: audio only, lowest-bitrate servers first, tiny buffers, no metadata, pause = disconnect.
 *  - Failover: any error / stall / end-of-stream switches to the next-best server immediately; when every
 *    server is dead it asks the cloud to heal the station, then retries by itself.
 *  - Learning: every success/failure feeds [com.example.radiojooo.data.Health] so ranking improves each time.
 */
@UnstableApi
class RadioService : Service() {

    companion object {
        private const val CHANNEL = "radio_joo_playback"
        private const val NOTIF_ID = 101
        private const val ACTION_PLAY = "com.example.radiojooo.PLAY"
        private const val ACTION_TOGGLE = "com.example.radiojooo.TOGGLE"
        private const val ACTION_STOP = "com.example.radiojooo.STOP"
        private const val ACTION_SLEEP = "com.example.radiojooo.SLEEP"
        private const val EXTRA_ID = "id"
        private const val EXTRA_MIN = "min"
        private const val FIRST_SERVER_TIMEOUT_MS = 9_000L
        private const val NEXT_SERVER_TIMEOUT_MS = 7_000L
        private const val REBUFFER_LIMIT_MS = 10_000L
        private const val MAX_AUTO_RETRIES = 4

        fun play(context: Context, stationId: String) {
            val i = Intent(context, RadioService::class.java).setAction(ACTION_PLAY).putExtra(EXTRA_ID, stationId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
        }
        fun toggle(context: Context) { context.startService(Intent(context, RadioService::class.java).setAction(ACTION_TOGGLE)) }
        fun stop(context: Context) { context.startService(Intent(context, RadioService::class.java).setAction(ACTION_STOP)) }
        fun sleepTimer(context: Context, minutes: Int) {
            context.startService(Intent(context, RadioService::class.java).setAction(ACTION_SLEEP).putExtra(EXTRA_MIN, minutes))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val totalBytes = AtomicLong(0)

    private lateinit var player: ExoPlayer
    private val repo by lazy { Graph.repo(this) }
    private val health by lazy { Graph.health(this) }

    // session state (main thread only)
    private var station: Station? = null
    private var order: List<Server> = emptyList()
    private var index = 0
    private var attemptStartedAt = 0L
    private var readyReported = false
    private var readyAt = 0L
    private var bytesAtReady = 0L
    private var bufferingSince = 0L
    private var healed = false
    private var autoRetries = 0
    private var status = Status.IDLE
    private var sessionSeconds = 0L
    private var sleepEndAt = 0L
    private var lastTickBytes = 0L
    private var speed = 0f
    private var measuredKbps = 0

    // ---------------------------------------------------------------- lifecycle
    override fun onCreate() {
        super.onCreate()
        createChannel()
        val transferListener = object : TransferListener {
            override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
            override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
                if (isNetwork) totalBytes.addAndGet(bytesTransferred.toLong())
            }
            override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
        }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("RadioJoo/3.0 (Android)")
            .setConnectTimeoutMs(5_000)
            .setReadTimeoutMs(8_000)
            .setAllowCrossProtocolRedirects(true)
            .setTransferListener(transferListener)

        // Data saver: small buffers (live radio gains nothing from buffering far ahead), nothing kept behind us.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(3_000, 8_000, 1_500, 2_500)
            .setTargetBufferBytes(256 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(0, false)
            .build()

        // Data saver: video can never be selected, and the cheapest audio rendition is always preferred.
        val selector = DefaultTrackSelector(this)
        selector.setParameters(
            selector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setForceLowestBitrate(true)
                .build()
        )

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setTrackSelector(selector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setAudioAttributes(
                AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build(), true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        player.addListener(playerListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                goForeground()
                val st = repo.find(intent?.getStringExtra(EXTRA_ID) ?: "")
                if (st == null) { stopEverything(); return START_NOT_STICKY }
                startStation(st)
            }
            ACTION_TOGGLE -> toggle()
            ACTION_STOP -> stopEverything()
            ACTION_SLEEP -> {
                val min = intent?.getIntExtra(EXTRA_MIN, 0) ?: 0
                sleepEndAt = if (min > 0) SystemClock.elapsedRealtime() + min * 60_000L else 0L
                publish()
            }
            else -> if (station == null) stopEverything()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) { /* keep playing like a radio */ super.onTaskRemoved(rootIntent) }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        try { player.removeListener(playerListener); player.release() } catch (e: Exception) { }
        flushReports()
        PlaybackBus.state.value = PlaybackUi()
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- session control
    private fun startStation(st: Station) {
        station = st
        healed = false
        autoRetries = 0
        sessionSeconds = 0
        totalBytes.set(0); lastTickBytes = 0; speed = 0f; measuredKbps = 0
        order = health.rank(st.servers)
        main.removeCallbacks(tick); main.post(tick)
        startAttempt(0)
    }

    private fun toggle() {
        val st = station ?: return
        if (status == Status.PLAYING || status == Status.CONNECTING || status == Status.SWITCHING) {
            // Pausing a live stream = disconnect completely, so a paused radio costs 0 data.
            main.removeCallbacks(watchdog)
            player.stop()
            status = Status.PAUSED
            publish()
        } else if (status == Status.PAUSED || status == Status.ERROR) {
            order = health.rank(st.servers)
            autoRetries = 0
            startAttempt(0)
        }
    }

    private fun stopEverything() {
        main.removeCallbacksAndMessages(null)
        try { player.stop() } catch (e: Exception) { }
        status = Status.IDLE
        station = null
        PlaybackBus.state.value = PlaybackUi()
        flushReports()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAttempt(i: Int) {
        main.removeCallbacks(watchdog)
        if (i >= order.size) { allServersFailed(); return }
        index = i
        readyReported = false
        bufferingSince = 0
        attemptStartedAt = SystemClock.elapsedRealtime()
        status = if (i == 0) Status.CONNECTING else Status.SWITCHING
        publish()
        val s = order[i]
        try {
            player.setMediaItem(itemFor(s))
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            onServerFailed()
            return
        }
        main.postDelayed(watchdog, if (i == 0) FIRST_SERVER_TIMEOUT_MS else NEXT_SERVER_TIMEOUT_MS)
    }

    private fun itemFor(s: Server): MediaItem {
        val b = MediaItem.Builder()
        if (s.hls) b.setUri(Api.BASE + "/r?u=" + URLEncoder.encode(s.url, "UTF-8")).setMimeType(MimeTypes.APPLICATION_M3U8)
        else b.setUri(s.url)
        return b.build()
    }

    /** Current server failed: remember it, jump to the next one right away. */
    private fun onServerFailed() {
        main.removeCallbacks(watchdog)
        val failed = order.getOrNull(index)
        if (failed != null) health.failure(failed.url)
        if (status == Status.IDLE || status == Status.PAUSED) return
        if (readyReported) {
            // it played fine before and then broke: re-rank (the broken one is now cooling) and start over from the best
            val st = station ?: return
            order = health.rank(st.servers)
            startAttempt(0)
        } else {
            startAttempt(index + 1)
        }
    }

    private fun allServersFailed() {
        val st = station ?: return
        if (!healed) {
            healed = true
            status = Status.HEALING
            publish()
            scope.launch {
                val fresh = withContext(Dispatchers.IO) { Api.heal(st.id) }
                if (station?.id != st.id || status == Status.IDLE) return@launch
                if (fresh != null && fresh.servers.isNotEmpty()) {
                    repo.updateStation(fresh)
                    station = fresh
                    order = health.rank(fresh.servers)
                    startAttempt(0)
                } else {
                    scheduleAutoRetry()
                }
            }
        } else {
            scheduleAutoRetry()
        }
    }

    private fun scheduleAutoRetry() {
        status = Status.ERROR
        publish()
        if (autoRetries >= MAX_AUTO_RETRIES) return
        autoRetries++
        main.postDelayed({
            val st = station ?: return@postDelayed
            if (status != Status.ERROR) return@postDelayed
            order = health.rank(st.servers)
            startAttempt(0)
        }, 15_000L)
    }

    private val watchdog = Runnable { if (!readyReported) onServerFailed() }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> if (bufferingSince == 0L) bufferingSince = SystemClock.elapsedRealtime()
                Player.STATE_READY -> { bufferingSince = 0; onReady() }
                Player.STATE_ENDED -> onServerFailed()
                else -> {}
            }
        }
        override fun onPlayerError(error: PlaybackException) { onServerFailed() }
    }

    private fun onReady() {
        if (readyReported) return
        readyReported = true
        main.removeCallbacks(watchdog)
        val now = SystemClock.elapsedRealtime()
        order.getOrNull(index)?.let { health.success(it.url, now - attemptStartedAt) }
        readyAt = now
        bytesAtReady = totalBytes.get()
        autoRetries = 0
        status = Status.PLAYING
        publish()
    }

    // ---------------------------------------------------------------- 1 Hz tick: counters, sleep timer, stall detection
    private val tick = object : Runnable {
        override fun run() {
            if (station == null) return
            val now = SystemClock.elapsedRealtime()
            val b = totalBytes.get()
            speed = (b - lastTickBytes) / 1024f
            lastTickBytes = b
            if (status == Status.PLAYING) sessionSeconds++

            // learn the real bitrate of this server after 20 s of stable playing
            if (status == Status.PLAYING && readyReported && measuredKbps == 0 && now - readyAt >= 20_000) {
                val k = ((totalBytes.get() - bytesAtReady) * 8 / 1000 / ((now - readyAt) / 1000)).toInt()
                measuredKbps = k
                order.getOrNull(index)?.let { health.measured(it.url, k) }
            }
            // stall after having played: switch instead of waiting forever
            if (readyReported && bufferingSince != 0L && now - bufferingSince > REBUFFER_LIMIT_MS && status == Status.PLAYING) {
                bufferingSince = 0
                onServerFailed()
            }
            if (sleepEndAt != 0L && now >= sleepEndAt) { stopEverything(); return }
            publish()
            main.postDelayed(this, 1_000L)
        }
    }

    // ---------------------------------------------------------------- UI + notification
    private fun publish() {
        val st = station
        val sleepLeft = if (sleepEndAt == 0L) 0L else maxOf(0L, (sleepEndAt - SystemClock.elapsedRealtime()) / 1000)
        val cur = order.getOrNull(index)
        PlaybackBus.state.value = PlaybackUi(
            station = st, status = status, serverIndex = index + 1, serverCount = order.size,
            kbps = if (measuredKbps > 0 && status == Status.PLAYING) measuredKbps else (cur?.kbps ?: 0),
            speedKBps = if (status == Status.PLAYING) speed else 0f,
            seconds = sessionSeconds, bytes = totalBytes.get(), sleepRemainingSec = sleepLeft
        )
        if (st != null) getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification())
    }

    private fun goForeground() {
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
    }

    private fun buildNotification(): Notification {
        val playing = status == Status.PLAYING || status == Status.CONNECTING || status == Status.SWITCHING
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggle = PendingIntent.getService(this, 1, Intent(this, RadioService::class.java).setAction(ACTION_TOGGLE), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, RadioService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val text = when (status) {
            Status.PLAYING -> "يعمل الآن"
            Status.CONNECTING -> "جاري الاتصال..."
            Status.SWITCHING -> "تبديل الخادم ${index + 1}/${order.size}"
            Status.HEALING -> "البحث عن خوادم جديدة..."
            Status.PAUSED -> "متوقف"
            Status.ERROR -> "تعذر التشغيل، إعادة المحاولة..."
            Status.IDLE -> ""
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification_play)
            .setContentTitle(station?.name ?: "راديو جو")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(if (playing) R.drawable.ic_notification_pause else R.drawable.ic_notification_play, if (playing) "إيقاف مؤقت" else "تشغيل", toggle)
            .addAction(R.drawable.ic_notification_stop, "إيقاف", stop)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 1))
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "تشغيل الراديو", NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }

    /** Send what this phone learned to the cloud (tiny), so every other phone benefits too. */
    private fun flushReports() {
        val items = health.drainReports()
        if (items.isEmpty()) return
        Thread {
            try { if (!Api.report(items)) health.requeue(items) } catch (e: Exception) { health.requeue(items) }
        }.start()
    }
}
