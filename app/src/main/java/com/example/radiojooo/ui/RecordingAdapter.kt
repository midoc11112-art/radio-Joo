package com.example.radiojooo.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.radiojooo.databinding.ItemRecordingBinding
import java.io.File

data class RecItem(val file: File, val title: String, val meta: String)

class RecordingAdapter(
    private val onPlay: (RecItem) -> Unit,
    private val onShare: (RecItem) -> Unit,
    private val onDelete: (RecItem) -> Unit
) : ListAdapter<RecItem, RecordingAdapter.VH>(Diff) {

    private var playingPath: String? = null
    private var isPlaying = false

    fun setPlaying(path: String?, playing: Boolean) {
        playingPath = path; isPlaying = playing
        notifyItemRangeChanged(0, itemCount)
    }

    object Diff : DiffUtil.ItemCallback<RecItem>() {
        override fun areItemsTheSame(a: RecItem, b: RecItem) = a.file.absolutePath == b.file.absolutePath
        override fun areContentsTheSame(a: RecItem, b: RecItem) = a == b
    }

    inner class VH(val b: ItemRecordingBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemRecordingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = getItem(position)
        h.b.tvRecName.text = item.title
        h.b.tvRecMeta.text = item.meta
        val mine = item.file.absolutePath == playingPath
        h.b.btnRecPlay.text = if (mine && isPlaying) "⏸ إيقاف مؤقت" else if (mine) "▶ متابعة" else "▶ تشغيل"
        h.b.btnRecPlay.setOnClickListener { onPlay(item) }
        h.b.btnRecShare.setOnClickListener { onShare(item) }
        h.b.btnRecDelete.setOnClickListener { onDelete(item) }
    }
}
