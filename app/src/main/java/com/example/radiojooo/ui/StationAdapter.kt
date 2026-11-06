package com.example.radiojooo.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.radiojooo.R
import com.example.radiojooo.data.model.StationEntity
import com.example.radiojooo.databinding.ItemStationBinding

class StationAdapter(
    private val onItemClick: (StationEntity) -> Unit,
    private val onFavoriteClick: (StationEntity) -> Unit
) : ListAdapter<StationEntity, StationAdapter.StationViewHolder>(StationDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StationViewHolder {
        val binding = ItemStationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return StationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class StationViewHolder(private val binding: ItemStationBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(station: StationEntity) {
            binding.tvStationName.text = station.name
            binding.tvCountryBadge.text = if (station.countryCode.isBlank()) "EG" else station.countryCode.uppercase()
            binding.tvBitrateBadge.text = if (station.bitrate > 0) "${station.bitrate} Kbps" else "غير محدد"

            val categoryText = when (station.category) {
                "QURAN" -> "القرآن الكريم"
                "NEWS" -> "الأخبار"
                "MUSIC" -> "موسيقى"
                "SPORTS" -> "الرياضة"
                "TALK" -> "توك شو"
                "EGYPT" -> "مصر"
                else -> "عام"
            }
            binding.tvCategoryBadge.text = categoryText

            val serverCount = if (station.fallbackUrl.isNotBlank()) station.fallbackUrl.split(",").filter { it.isNotBlank() }.size + 1 else 1
            binding.tvServersBadge.text = "$serverCount خوادم"

            binding.btnFavorite.setImageResource(if (station.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
            binding.root.setOnClickListener { onItemClick(station) }
            binding.btnFavorite.setOnClickListener { onFavoriteClick(station) }
        }
    }

    class StationDiffCallback : DiffUtil.ItemCallback<StationEntity>() {
        override fun areItemsTheSame(oldItem: StationEntity, newItem: StationEntity) = oldItem.uuid == newItem.uuid
        override fun areContentsTheSame(oldItem: StationEntity, newItem: StationEntity) = oldItem == newItem
    }
}
