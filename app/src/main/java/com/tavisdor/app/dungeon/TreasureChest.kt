package com.tavisdor.app.dungeon

import com.tavisdor.app.items.LootDrop

/**
 * Runtime state for a treasure chest on one floor cell.
 *
 * Visual states (sprites):
 *   - [CLOSED]  -> `treasure1` (never opened, or locked and not yet opened)
 *   - [OPENED]  -> `treasure2` (opened, loot may remain inside)
 *   - [EMPTY]   -> `treasure3` (all gold and items taken)
 *
 * Lock rules mirror [Door]: Thief pick (DEX + shard), STR force once,
 * or a matching [com.tavisdor.app.items.FloorKey] from this floor.
 */
class TreasureChest(
    val cell: Cell,
    val lockId: String,
    var locked: Boolean,
    var bruteDamaged: Boolean = false,
    var strForceAttempted: Boolean = false,
) {
    enum class VisualState { CLOSED, OPENED, EMPTY }

    var loot: ChestLoot = ChestLoot()
    /** Set when the player first opens the chest; drives sprite and loot roll. */
    var lootRolled: Boolean = false

    val visualState: VisualState
        get() = when {
            !lootRolled -> VisualState.CLOSED
            loot.isEmpty() -> VisualState.EMPTY
            else -> VisualState.OPENED
        }

    fun unlock() {
        locked = false
    }
}

/** Gold and item drops remaining inside an opened chest. */
data class ChestLoot(
    var gold: Int = 0,
    val items: MutableList<LootDrop> = mutableListOf(),
) {
    fun isEmpty(): Boolean = gold <= 0 && items.isEmpty()

    fun removeGold(amount: Int): Int {
        if (gold <= 0) return 0
        val taken = amount.coerceAtMost(gold)
        gold -= taken
        return taken
    }

    fun removeItemAt(index: Int): LootDrop? {
        if (index !in items.indices) return null
        return items.removeAt(index)
    }
}
