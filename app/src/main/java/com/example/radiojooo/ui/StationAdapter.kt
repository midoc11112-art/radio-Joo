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
        val binding = ItemStationBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return StationViewHolder(binding)
    }

    override fun onBindViewHolder(holder: StationViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class StationViewHolder(private val binding: ItemStationBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(station: StationEntity) {
            binding.tvStationName.text = station.name
            binding.tvCountryBadge.text = if (station.countryCode.isBlank()) "EG" else station.countryCode.uppercase()
            binding.tvBitrateBadge.text = "${if (station.bitrate <= 0) 32 else station.bitrate} Kbps"

            val categoryText = when (station.category) {
                "QURAN" -> "القرآن الكريم"
                "NEWS" -> "الأخبار"
                "MUSIC" -> "موسيقى"
                "SPORTS" -> "الرياضة"
                else -> "عام"
            }
            binding.tvCategoryBadge.text = categoryText

            val serverCount = if (station.fallbackUrl.isNotBlank()) {
                station.fallbackUrl.split(",").filter { it.isNotBlank() }.size + 1
            } else 1
            binding.tvServersBadge.text = "$serverCount خوادم"

            if (station.isFavorite) {
                binding.btnFavorite.setImageResource(R.drawable.ic_star_filled)
            } else {
                binding.btnFavorite.setImageResource(R.drawable.ic_star_outline)
            }

            binding.root.setOnClickListener {
                onItemClick(station)
            }

            binding.btnFavorite.setOnClickListener {
                onFavoriteClick(station)
            }
        }
    }

    class StationDiffCallback : DiffUtil.ItemCallback<StationEntity>() {
        override fun areItemsTheSame(oldItem: StationEntity, newItem: StationEntity): Boolean {
            return oldItem.uuid == newItem.uuid
        }

        override fun areContentsTheSame(oldItem: StationEntity, newItem: StationEntity): Boolean {
            return oldItem == newItem
        }
    }
}
