package com.tavisdor.app.items

/**
 * Fixed slot grid per inventory tab (Loot, Equipment, Materials).
 */
object InventoryCapacity {
    const val GRID_ROWS: Int = 3
    const val GRID_COLS: Int = 4
    const val SLOTS_PER_TAB: Int = GRID_ROWS * GRID_COLS
}
