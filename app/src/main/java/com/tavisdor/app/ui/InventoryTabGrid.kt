package com.tavisdor.app.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tavisdor.app.R
import com.tavisdor.app.items.InventoryCapacity
import com.tavisdor.app.items.InventoryGridSlot

/**
 * Fixed 3×4 inventory grid (same cell size as hero equipment slots).
 * Slot borders tint to match the active inventory tab.
 */
class InventoryTabGrid(
    private val grid: GridLayout,
) {
    private val ctx: Context get() = grid.context
    private val inflater: LayoutInflater = LayoutInflater.from(ctx)
    private val slotSizePx: Int =
        ctx.resources.getDimensionPixelSize(R.dimen.inventory_equip_slot_size)
    private val gapPx: Int =
        ctx.resources.getDimensionPixelSize(R.dimen.inventory_equip_grid_gap)

    private val cells: List<CellViews> = buildCells()
    private var clickHandler: ((Int) -> Unit)? = null
    private var slotFilled: Drawable? = null
    private var slotEmpty: Drawable? = null
    private var lastSlots: List<InventoryGridSlot> = emptyList()

    private data class CellViews(
        val root: View,
        val label: TextView,
        val count: TextView,
    )

    init {
        grid.columnCount = InventoryCapacity.GRID_COLS
        grid.rowCount = InventoryCapacity.GRID_ROWS
        applyTheme(InventoryPanelTab.PICKUP)
    }

    fun applyTheme(tab: InventoryPanelTab) {
        val accent = ContextCompat.getColor(ctx, tab.accentColorRes)
        slotFilled = InventorySlotDrawables.slot(ctx, accent, filled = true)
        slotEmpty = InventorySlotDrawables.slot(ctx, accent, filled = false)
        if (lastSlots.isNotEmpty()) {
            bind(lastSlots)
        }
    }

    fun setOnSlotClick(handler: ((Int) -> Unit)?) {
        clickHandler = handler
        cells.forEachIndexed { index, cell ->
            val active = handler != null && index < InventoryCapacity.SLOTS_PER_TAB
            cell.root.isClickable = active
            cell.root.isFocusable = active
        }
    }

    /**
     * @param slots One entry per occupied grid cell (stacked items use [InventoryGridSlot.count]).
     * Empty slots render blank.
     */
    fun bind(slots: List<InventoryGridSlot>) {
        lastSlots = slots
        val filledBg = slotFilled
        val emptyBg = slotEmpty
        for (index in 0 until InventoryCapacity.SLOTS_PER_TAB) {
            val slot = slots.getOrNull(index)
            val cell = cells[index]
            if (slot == null || slot.label.isBlank()) {
                cell.label.text = ""
                cell.count.visibility = View.GONE
                cell.label.typeface = Typeface.DEFAULT
                cell.root.background = emptyBg
                cell.label.alpha = 0.45f
            } else {
                cell.label.text = slot.label
                cell.label.typeface = Typeface.DEFAULT_BOLD
                cell.root.background = filledBg
                cell.label.alpha = 1f
                if (slot.count > 1) {
                    cell.count.text = slot.count.toString()
                    cell.count.visibility = View.VISIBLE
                } else {
                    cell.count.visibility = View.GONE
                }
            }
            cell.root.alpha = 1f
        }
    }

    private fun buildCells(): List<CellViews> {
        val out = ArrayList<CellViews>(InventoryCapacity.SLOTS_PER_TAB)
        for (index in 0 until InventoryCapacity.SLOTS_PER_TAB) {
            val root = inflater.inflate(R.layout.item_inventory_grid_cell, grid, false)
            val label = root.findViewById<TextView>(R.id.tvInventoryGridCellLabel)
            val count = root.findViewById<TextView>(R.id.tvInventoryGridCellCount)
            val row = index / InventoryCapacity.GRID_COLS
            val col = index % InventoryCapacity.GRID_COLS
            val params = GridLayout.LayoutParams().apply {
                width = slotSizePx
                height = slotSizePx
                columnSpec = GridLayout.spec(col)
                rowSpec = GridLayout.spec(row)
                setMargins(
                    if (col == 0) 0 else gapPx / 2,
                    if (row == 0) 0 else gapPx / 2,
                    if (col == InventoryCapacity.GRID_COLS - 1) 0 else gapPx / 2,
                    if (row == InventoryCapacity.GRID_ROWS - 1) 0 else gapPx / 2,
                )
            }
            root.layoutParams = params
            root.setOnClickListener {
                if (index < InventoryCapacity.SLOTS_PER_TAB) {
                    clickHandler?.invoke(index)
                }
            }
            grid.addView(root)
            out += CellViews(root, label, count)
        }
        return out
    }
}
