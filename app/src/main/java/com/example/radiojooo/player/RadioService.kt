package com.example.radiojooo.player

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@OptIn(UnstableApi::class)
class RadioService : MediaSessionService() {

    companion object {
        @Volatile
        private var instance: RadioService? = null

        fun getInstance(): RadioService? = instance
    }

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        instance = this

        initLocks()
        initPlayer()
        initMediaSession()
        setupNotificationProvider()
    }

    private fun initLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RadioJoo::WakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "RadioJoo::WifiLock"
        ).apply {
            setReferenceCounted(false)
        }
    }

    private fun initPlayer() {
        // 1. تحديد خصائص الصوت ليعرف النظام أنه تشغيل موسيقى/راديو
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        exoPlayer = ExoPlayer.Builder(this)
            // 2. إعطاء الخيار true لـ setAudioAttributes يُمكّن إدارة Audio Focus آلياً من Media3
            .setAudioAttributes(audioAttributes, true)
            // 3. معالجة فصل السماعات تلقائياً (Audio Becoming Noisy)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) {
                            acquireLocks()
                        } else {
                            releaseLocks()
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                            releaseLocks()
                        }
                    }
                })
            }
    }

    private fun initMediaSession() {
        val player = exoPlayer ?: return

        val forwardingPlayer = object : ForwardingPlayer(player) {
            override fun stop() {
                super.stop()
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setCallback(MediaSessionCallback())
            .build()
    }

    private fun setupNotificationProvider() {
        val notificationProvider = DefaultMediaNotificationProvider.Builder(this).build()
        notificationProvider.setSmallIcon(android.R.drawable.ic_media_play)
        setMediaNotificationProvider(notificationProvider)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    fun playStation(url: String, title: String, stationSubtitle: String? = null) {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(stationSubtitle ?: "Radio Joo")
            .build()

        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(metadata)
            .build()

        exoPlayer?.apply {
            setMediaItem(mediaItem)
            prepare()
            play()
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun resume() {
        exoPlayer?.play()
    }

    fun stop() {
        exoPlayer?.stop()
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    fun isPlaying(): Boolean {
        return exoPlayer?.isPlaying == true
    }

    fun getPlayer(): ExoPlayer? = exoPlayer

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        try {
            if (wakeLock?.isHeld == false) {
                wakeLock?.acquire()
            }
            if (wifiLock?.isHeld == false) {
                wifiLock?.acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val player = exoPlayer
        if (player == null || !player.isPlaying) {
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        releaseLocks()
        mediaSession?.run {
            player.release()
            release()
            mediaSession = null
        }
        exoPlayer = null
        instance = null
        super.onDestroy()
    }

    private inner class MediaSessionCallback : MediaSession.Callback
}
