package com.example.radiojooo.ui

import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import com.example.radiojooo.Graph
import com.example.radiojooo.R
import com.example.radiojooo.data.CategoryStyle
import com.example.radiojooo.databinding.ActivityPlayerBinding
import com.example.radiojooo.databinding.ItemServerBinding
import com.example.radiojooo.player.PlaybackBus
import com.example.radiojooo.player.PlaybackUi
import com.example.radiojooo.player.RadioService
import com.example.radiojooo.player.Status
import kotlinx.coroutines.launch
import java.util.Locale

/** Full player: live stats, manual server choice, recording, sleep timer, volume, favorite. */
@UnstableApi
class PlayerActivity : AppCompatActivity() {
    private lateinit var b: ActivityPlayerBinding
    private lateinit var audio: AudioManager
    private val repo by lazy { Graph.repo(this) }
    private var serversKey = ""
    private var artCategory = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(b.root)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        b.btnBack.setOnClickListener { finish() }
        b.btnPlayPause.setOnClickListener { RadioService.toggle(this) }
        b.btnStopRadio.setOnClickListener { RadioService.stop(this); finish() }
        b.btnNextServer.setOnClickListener { RadioService.nextServer(this) }
        b.btnRecord.setOnClickListener {
            val ui = PlaybackBus.state.value
            if (ui.recording || ui.canRecord) RadioService.record(this)
            else Toast.makeText(this, "التسجيل متاح أثناء التشغيل فقط، ولا يدعم بث HLS", Toast.LENGTH_SHORT).show()
        }
        b.btnRecordings.setOnClickListener { startActivity(Intent(this, RecordingsActivity::class.java)) }
        b.btnSleep.setOnClickListener { SleepDialog.show(this, PlaybackBus.state.value.sleepRemainingSec) }
        b.btnFav.setOnClickListener { PlaybackBus.state.value.station?.let { repo.toggleFavorite(it.id) } }

        b.sbVolume.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        b.sbVolume.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        b.sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { PlaybackBus.state.collect { render(it) } }
                launch {
                    repo.stations.collect { list ->
                        val id = PlaybackBus.state.value.station?.id
                        val fav = list.firstOrNull { it.id == id }?.isFavorite == true
                        b.btnFav.setImageResource(if (fav) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        b.sbVolume.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    private fun render(ui: PlaybackUi) {
        val st = ui.station
        if (st == null || ui.status == Status.IDLE) { finish(); return }

        b.tvName.text = st.name
        val cc = if (st.countryCode.isEmpty()) "AR" else st.countryCode
        b.tvSpecs.text = (if (ui.kbps > 0) "${ui.kbps} Kbps" else "— Kbps") + "  •  " + cc + "  •  " + CategoryStyle.arabicName(st.category)
        b.tvArtEmoji.text = CategoryStyle.emoji(st.category)
        if (artCategory != st.category) {
            artCategory = st.category
            val base = CategoryStyle.color(st.category)
            val light = android.graphics.Color.rgb(
                minOf(255, android.graphics.Color.red(base) + 70), minOf(255, android.graphics.Color.green(base) + 70), minOf(255, android.graphics.Color.blue(base) + 70)
            )
            b.artCard.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(light, base)).apply {
                cornerRadius = 28f * resources.displayMetrics.density
            }
        }

        val active = ui.status == Status.PLAYING || ui.status == Status.CONNECTING || ui.status == Status.SWITCHING
        b.tvStatus.text = when (ui.status) {
            Status.PLAYING -> "▶ يعمل الآن"
            Status.CONNECTING -> "جاري الاتصال..."
            Status.SWITCHING -> "تبديل الخادم ${ui.serverIndex}/${ui.serverCount}"
            Status.HEALING -> "البحث عن خوادم جديدة..."
            Status.PAUSED -> "⏸ متوقف — لا يستهلك أي إنترنت"
            Status.ERROR -> "تعذر التشغيل، إعادة المحاولة تلقائياً..."
            Status.IDLE -> ""
        }
        b.pbStatus.visibility = if (ui.status == Status.CONNECTING || ui.status == Status.SWITCHING || ui.status == Status.HEALING) View.VISIBLE else View.GONE
        b.btnPlayPause.text = if (active) "⏸ إيقاف" else "▶ تشغيل"

        b.tvSpeed.text = String.format(Locale.US, "%.1f KB/s", ui.speedKBps)
        b.tvDuration.text = time(ui.seconds)
        b.tvData.text = String.format(Locale.US, "%.2f MB", ui.bytes / 1048576.0)

        // recording
        b.btnRecord.text = if (ui.recording) "⏹ إيقاف التسجيل" else "⏺ بدء التسجيل"
        b.btnRecord.isEnabled = ui.recording || ui.canRecord
        b.tvRecInfo.text = when {
            ui.recording -> String.format(Locale.US, "🔴 يسجّل الآن: %s  •  %.2f MB", time(ui.recSeconds), ui.recBytes / 1048576.0)
            ui.status == Status.PLAYING && !ui.canRecord -> "التسجيل غير متاح لهذا الخادم (بث HLS)، جرّب خادماً آخر"
            else -> "التسجيل يحفظ نفس البث الذي تسمعه ولا يستهلك إنترنت إضافياً"
        }

        // sleep timer
        b.tvSleepInfo.text = if (ui.sleepRemainingSec > 0) "سيُغلق التطبيق بعد ${time(ui.sleepRemainingSec)}" else "غير مفعّل"

        renderServers(ui)
    }

    private fun renderServers(ui: PlaybackUi) {
        val key = ui.servers.joinToString("|") { it.url } + "#" + ui.currentIndex + "#" + ui.status
        if (key == serversKey) return
        serversKey = key
        b.llServers.removeAllViews()
        ui.servers.forEachIndexed { i, s ->
            val row = ItemServerBinding.inflate(LayoutInflater.from(this), b.llServers, false)
            row.tvSrvIndex.text = "${i + 1}"
            row.tvSrvHost.text = Uri.parse(s.url).host ?: s.url
            row.tvSrvKbps.text = if (s.kbps > 0) "${s.kbps} Kbps" else "— Kbps"
            row.tvSrvState.text = when {
                i != ui.currentIndex -> ""
                ui.status == Status.PLAYING -> "● يعمل"
                ui.status == Status.PAUSED -> "⏸"
                else -> "…"
            }
            row.root.setOnClickListener { RadioService.selectServer(this, i) }
            b.llServers.addView(row.root)
        }
    }

    private fun time(sec: Long): String = String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
}
