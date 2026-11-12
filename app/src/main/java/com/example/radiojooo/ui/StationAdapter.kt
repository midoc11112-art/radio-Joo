package com.example.radiojooo.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.radiojooo.R
import com.example.radiojooo.data.Station
import com.example.radiojooo.databinding.ItemStationBinding

class StationAdapter(
    private val onClick: (Station) -> Unit,
    private val onFavorite: (Station) -> Unit
) : ListAdapter<Station, StationAdapter.VH>(Diff) {

    private var playingId: String? = null

    fun setPlaying(id: String?) {
        if (id == playingId) return
        playingId = id
        notifyItemRangeChanged(0, itemCount)
    }

    object Diff : DiffUtil.ItemCallback<Station>() {
        override fun areItemsTheSame(a: Station, b: Station) = a.id == b.id
        override fun areContentsTheSame(a: Station, b: Station) = a == b
    }

    inner class VH(val b: ItemStationBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemStationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val s = getItem(position)
        h.b.tvStationName.text = s.name
        h.b.tvServersBadge.text = "${s.servers.size} خوادم"
        h.b.tvCountryBadge.text = if (s.countryCode.isEmpty()) "AR" else s.countryCode
        h.b.tvBitrateBadge.text = if (s.bitrate > 0) "${s.bitrate} Kbps" else "تلقائي"
        h.b.tvCategoryBadge.text = categoryName(s.category)
        h.b.btnFavorite.setImageResource(if (s.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
        
        // عند الضغط على شارة الخوادم: تفتح نافذة الخوادم والتثبيت والحظر
        h.b.tvServersBadge.setOnClickListener {
            ServerManagerDialog.show(h.itemView.context, s)
        }

        h.b.btnFavorite.setOnClickListener { onFavorite(s) }
        h.b.root.setOnClickListener { onClick(s) }
        h.b.root.setStrokeColor(if (s.id == playingId) Color.parseColor("#8B5CF6") else Color.parseColor("#1E293B"))
    }

    companion object {
        fun categoryName(c: String) = com.example.radiojooo.data.CategoryStyle.arabicName(c)
    }
}