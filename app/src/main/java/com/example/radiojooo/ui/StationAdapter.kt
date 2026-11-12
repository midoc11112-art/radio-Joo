package com.example.radiojooo.ui

import android.graphics.Canvas
import android.graphics.Color
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.radiojooo.R
import com.example.radiojooo.data.Station
import com.example.radiojooo.databinding.ItemStationBinding

@Suppress("DEPRECATION")
class StationAdapter(
    private val onClick: (Station) -> Unit,
    private val onFavorite: (Station) -> Unit,
    private val onMenu: (String, String) -> Unit,
    private val onOrder: (List<String>) -> Unit,
    private val onDragState: (Boolean) -> Unit = {}
) : RecyclerView.Adapter<StationAdapter.VH>() {

    private var items: MutableList<Station> = ArrayList()
    private var playingId: String? = null
    private var dragging = false
    private var pending: List<Station>? = null

    fun setPlaying(id: String?) {
        if (id == playingId) return
        playingId = id
        notifyItemRangeChanged(0, itemCount)
    }

    fun submitList(list: List<Station>) {
        if (dragging) { pending = list; return }
        val old = items
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = list.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].id == list[n].id
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == list[n]
        }, true)
        items = ArrayList(list)
        diff.dispatchUpdatesTo(this)
    }

    fun stationAt(pos: Int): Station? = items.getOrNull(pos)
    fun beginDrag() { dragging = true; onDragState(true) }

    fun move(from: Int, to: Int): Boolean {
        if (from !in items.indices || to !in items.indices) return false
        val s = items.removeAt(from)
        items.add(to, s)
        notifyItemMoved(from, to)
        return true
    }

    fun endDrag(moved: Boolean) {
        dragging = false
        onDragState(false)
        if (moved) {
            pending = null
            onOrder(items.map { it.id })
        } else {
            val p = pending
            pending = null
            if (p != null) submitList(p)
        }
    }

    fun requestMenu(id: String, name: String) = onMenu(id, name)

    inner class VH(val b: ItemStationBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemStationBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val s = items[position]
        h.b.tvStationName.text = s.name
        h.b.tvServersBadge.text = "${s.servers.size} \u062E\u0648\u0627\u062F\u0645"
        h.b.tvCountryBadge.text = if (s.countryCode.isEmpty()) "AR" else s.countryCode
        h.b.tvBitrateBadge.text = if (s.bitrate > 0) "${s.bitrate} Kbps" else "\u2014 Kbps"
        h.b.tvCategoryBadge.text = categoryName(s.category)
        h.b.btnFavorite.setImageResource(if (s.isFavorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
        h.b.btnFavorite.setOnClickListener { onFavorite(s) }
        h.b.root.setOnClickListener { onClick(s) }
        h.b.root.setStrokeColor(if (s.id == playingId) Color.parseColor("#8B5CF6") else Color.parseColor("#1E293B"))
    }

    class TouchCallback(private val adapter: StationAdapter, private val slop: Int) : ItemTouchHelper.Callback() {
        private var active = false
        private var moved = false
        private var maxDisp = 0f
        private var heldId: String? = null
        private var heldName = ""

        override fun isLongPressDragEnabled() = true
        override fun isItemViewSwipeEnabled() = false
        override fun getMovementFlags(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) =
            makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

        override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val from = viewHolder.adapterPosition
            val to = target.adapterPosition
            if (from < 0 || to < 0) return false
            val ok = adapter.move(from, to)
            if (ok) moved = true
            return ok
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(viewHolder, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
                val s = adapter.stationAt(viewHolder.adapterPosition)
                active = s != null
                moved = false; maxDisp = 0f; heldId = s?.id; heldName = s?.name ?: ""
                if (!active) return
                adapter.beginDrag()
                viewHolder.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                viewHolder.itemView.alpha = 0.92f
                viewHolder.itemView.scaleX = 1.03f
                viewHolder.itemView.scaleY = 1.03f
            } else if (actionState == ItemTouchHelper.ACTION_STATE_IDLE && active) {
                active = false
                val id = heldId
                val name = heldName
                val didMove = moved || maxDisp >= slop
                adapter.endDrag(moved)
                if (!didMove && id != null) adapter.requestMenu(id, name)
            }
        }

        override fun onChildDraw(c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean) {
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && isCurrentlyActive) {
                val d = kotlin.math.hypot(dX, dY)
                if (d > maxDisp) maxDisp = d
            }
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            viewHolder.itemView.alpha = 1f
            viewHolder.itemView.scaleX = 1f
            viewHolder.itemView.scaleY = 1f
        }
    }

    companion object {
        fun categoryName(c: String) = com.example.radiojooo.data.CategoryStyle.arabicName(c)
    }
}