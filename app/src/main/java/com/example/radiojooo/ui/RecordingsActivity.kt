package com.example.radiojooo.ui

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.radiojooo.databinding.ActivityRecordingsBinding
import com.example.radiojooo.player.PlaybackBus
import com.example.radiojooo.player.RadioService
import com.example.radiojooo.player.Recorder
import com.example.radiojooo.player.Status
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Lists recordings with full control: play / pause / seek, share, delete. */
@UnstableApi
class RecordingsActivity : AppCompatActivity() {
    private lateinit var b: ActivityRecordingsBinding
    private lateinit var adapter: RecordingAdapter
    private var player: MediaPlayer? = null
    private var playingFile: File? = null
    private val handler = Handler(Looper.getMainLooper())

    private val progressTick = object : Runnable {
        override fun run() {
            val p = player ?: return
            try {
                if (!b.sbRec.isPressed) { b.sbRec.max = p.duration; b.sbRec.progress = p.currentPosition }
                b.tvRecTime.text = mmss(p.currentPosition) + " / " + mmss(p.duration)
            } catch (e: Exception) { }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRecordingsBinding.inflate(layoutInflater)
        setContentView(b.root)

        adapter = RecordingAdapter(onPlay = { play(it) }, onShare = { share(it) }, onDelete = { confirmDelete(it) })
        b.rvRecordings.layoutManager = LinearLayoutManager(this)
        b.rvRecordings.adapter = adapter
        b.btnRecBack.setOnClickListener { finish() }
        b.btnRecPlayPause.setOnClickListener { togglePlayback() }
        b.sbRec.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) try { player?.seekTo(progress) } catch (e: Exception) { }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    private fun load() {
        lifecycleScope.launch {
            val active = Recorder.state.value.fileName
            val items = withContext(Dispatchers.IO) {
                val files = Recorder.dir(this@RecordingsActivity).listFiles { f -> f.isFile && f.length() > 0 && f.name != active }
                    ?.sortedByDescending { it.lastModified() } ?: emptyList()
                files.map { f ->
                    val title = f.name.substringBeforeLast('.').substringBefore("__").replace('_', ' ')
                    val date = SimpleDateFormat("yyyy/MM/dd  HH:mm", Locale.US).format(Date(f.lastModified()))
                    val mb = String.format(Locale.US, "%.1f MB", f.length() / 1048576.0)
                    val dur = durationMs(f)
                    RecItem(f, title, date + "  •  " + mb + (if (dur > 0) "  •  " + mmss(dur.toInt()) else ""))
                }
            }
            adapter.submitList(items)
            b.tvRecEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            b.tvRecTitle.text = "📂 التسجيلات (${items.size})"
        }
    }

    private fun durationMs(f: File): Long = try {
        val r = MediaMetadataRetriever()
        r.setDataSource(f.absolutePath)
        val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        r.release()
        d
    } catch (e: Exception) { 0L }

    private fun play(item: RecItem) {
        if (playingFile == item.file && player != null) { togglePlayback(); return }
        stopPlayback()
        // the radio and a recording must not talk over each other
        if (PlaybackBus.state.value.status == Status.PLAYING) RadioService.toggle(this)
        val mp = MediaPlayer()
        try {
            mp.setDataSource(item.file.absolutePath)
            mp.setOnPreparedListener {
                it.start()
                b.recPlayerBar.visibility = View.VISIBLE
                b.tvNowPlaying.text = item.title
                b.btnRecPlayPause.text = "⏸"
                adapter.setPlaying(item.file.absolutePath, true)
                handler.removeCallbacks(progressTick); handler.post(progressTick)
            }
            mp.setOnCompletionListener { stopPlayback() }
            mp.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, "تعذر تشغيل هذا التسجيل", Toast.LENGTH_SHORT).show()
                stopPlayback(); true
            }
            player = mp
            playingFile = item.file
            mp.prepareAsync()
        } catch (e: Exception) {
            try { mp.release() } catch (x: Exception) { }
            player = null; playingFile = null
            Toast.makeText(this, "تعذر فتح الملف", Toast.LENGTH_SHORT).show()
        }
    }

    private fun togglePlayback() {
        val p = player ?: return
        try {
            if (p.isPlaying) { p.pause(); b.btnRecPlayPause.text = "▶"; adapter.setPlaying(playingFile?.absolutePath, false) }
            else { p.start(); b.btnRecPlayPause.text = "⏸"; adapter.setPlaying(playingFile?.absolutePath, true) }
        } catch (e: Exception) { stopPlayback() }
    }

    private fun stopPlayback() {
        handler.removeCallbacks(progressTick)
        try { player?.release() } catch (e: Exception) { }
        player = null
        playingFile = null
        if (::b.isInitialized) {
            b.recPlayerBar.visibility = View.GONE
            adapter.setPlaying(null, false)
        }
    }

    private fun share(item: RecItem) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", item.file)
            val send = Intent(Intent.ACTION_SEND).setType("audio/*")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, "مشاركة التسجيل"))
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر مشاركة الملف", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(item: RecItem) {
        MaterialAlertDialogBuilder(this, com.example.radiojooo.R.style.RadioDialog)
            .setTitle("حذف التسجيل؟")
            .setMessage(item.title)
            .setPositiveButton("حذف") { _, _ ->
                if (playingFile == item.file) stopPlayback()
                item.file.delete()
                load()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun mmss(ms: Int): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
        else String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }
}
