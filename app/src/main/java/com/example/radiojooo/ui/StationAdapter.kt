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

    inner class StationViewHolder(
        private val binding: ItemStationBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(station: StationEntity) {
            binding.tvStationName.text = station.name
            binding.tvCategory.text = station.category

            binding.root.setOnClickListener {
                onItemClick(station)
            }

            binding.btnFavorite.setOnClickListener {
                onFavoriteClick(station)
            }

            val favIcon = if (station.isFavorite) {
                R.drawable.ic_favorite_filled
            } else {
                R.drawable.ic_favorite_border
            }
            binding.btnFavorite.setImageResource(favIcon)
        }
    }

    class StationDiffCallback : DiffUtil.ItemCallback<StationEntity>() {
        override fun areItemsTheSame(oldItem: StationEntity, newItem: StationEntity): Boolean {
            return oldItem.uuid == newItem.uuid
        }

        override fun areContentTheSame(oldItem: StationEntity, newItem: StationEntity): Boolean {
            return oldItem == newItem
        }
    }
}
