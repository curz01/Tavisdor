package com.tavisdor.app.items

/**
 * One occupied cell in a 3×4 inventory tab grid. [count] > 1 shows a
 * stack badge on the slot.
 */
data class InventoryGridSlot(
    val label: String,
    val count: Int = 1,
) {
    init {
        require(count >= 1)
    }
}
