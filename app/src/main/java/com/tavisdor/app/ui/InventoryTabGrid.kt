package com.tavisdor.app.ui

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.DrawableRes
import android.view.LayoutInflater
import android.view.View
import android.widget.GridLayout
import android.widget.TextView
import com.tavisdor.app.R
import com.tavisdor.app.items.InventoryCapacity

/**
 * Fixed 3×4 inventory grid (same cell size as hero equipment slots).
 */
class InventoryTabGrid(
    private val grid: GridLayout,
    @DrawableRes private val slotBackgroundRes: Int,
    @DrawableRes private val slotBackgroundEmptyRes: Int,
) {
    private val ctx: Context get() = grid.context
    private val inflater: LayoutInflater = LayoutInflater.from(ctx)
    private val slotSizePx: Int =
        ctx.resources.getDimensionPixelSize(R.dimen.inventory_equip_slot_size)
    private val gapPx: Int =
        ctx.resources.getDimensionPixelSize(R.dimen.inventory_equip_grid_gap)

    private val cells: List<CellViews> = buildCells()
    private var clickHandler: ((Int) -> Unit)? = null

    private data class CellViews(
        val root: View,
        val label: TextView,
    )

    init {
        grid.columnCount = InventoryCapacity.GRID_COLS
        grid.rowCount = InventoryCapacity.GRID_ROWS
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
     * @param labels One label per occupied slot, in display order.
     * Empty slots render blank.
     */
    fun bind(labels: List<String>) {
        for (index in 0 until InventoryCapacity.SLOTS_PER_TAB) {
            val label = labels.getOrNull(index)
            val cell = cells[index]
            if (label.isNullOrBlank()) {
                cell.label.text = ""
                cell.label.typeface = Typeface.DEFAULT
                cell.root.setBackgroundResource(slotBackgroundEmptyRes)
                cell.label.alpha = 0.45f
            } else {
                cell.label.text = label
                cell.label.typeface = Typeface.DEFAULT_BOLD
                cell.root.setBackgroundResource(slotBackgroundRes)
                cell.label.alpha = 1f
            }
            cell.root.alpha = 1f
        }
    }

    private fun buildCells(): List<CellViews> {
        val out = ArrayList<CellViews>(InventoryCapacity.SLOTS_PER_TAB)
        for (index in 0 until InventoryCapacity.SLOTS_PER_TAB) {
            val root = inflater.inflate(R.layout.item_inventory_grid_cell, grid, false)
            val label = root.findViewById<TextView>(R.id.tvInventoryGridCellLabel)
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
            root.setBackgroundResource(slotBackgroundRes)
            root.setOnClickListener {
                if (index < InventoryCapacity.SLOTS_PER_TAB) {
                    clickHandler?.invoke(index)
                }
            }
            grid.addView(root)
            out += CellViews(root, label)
        }
        return out
    }
}
