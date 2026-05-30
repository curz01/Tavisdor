package com.tavisdor.app.items

/**
 * Groups flat inventory rows into display stacks (label + count) and
 * maps a stack index back to a backing list index for single-item removal.
 */
object InventoryStacks {

    fun <T> slots(
        items: List<T>,
        label: (T) -> String,
        sameItem: (T, T) -> Boolean,
    ): List<InventoryGridSlot> {
        if (items.isEmpty()) return emptyList()
        val order = ArrayList<T>()
        val counts = LinkedHashMap<T, Int>()
        for (item in items) {
            val key = order.firstOrNull { sameItem(it, item) }
            if (key != null) {
                counts[key] = counts.getValue(key) + 1
            } else {
                order += item
                counts[item] = 1
            }
        }
        return order.map { rep ->
            InventoryGridSlot(label(rep), counts.getValue(rep))
        }
    }

    fun <T> stackCount(
        items: List<T>,
        sameItem: (T, T) -> Boolean,
    ): Int = slots(items, label = { "" }, sameItem = sameItem).size

    /**
     * Index in [items] of one instance belonging to stack [stackIndex]
     * (first-seen stack order), or null when out of range.
     */
    fun <T> backingIndexForStack(
        items: List<T>,
        stackIndex: Int,
        sameItem: (T, T) -> Boolean,
    ): Int? {
        if (items.isEmpty() || stackIndex < 0) return null
        val order = ArrayList<T>()
        for (item in items) {
            if (order.none { sameItem(it, item) }) {
                order += item
            }
        }
        val rep = order.getOrNull(stackIndex) ?: return null
        return items.indexOfFirst { sameItem(it, rep) }.takeIf { it >= 0 }
    }

    fun lootDropSlots(
        drops: List<LootDrop>,
        label: (LootDrop) -> String,
    ): List<InventoryGridSlot> =
        slots(drops, label = label, sameItem = ::lootDropsMatch)

    fun lootDropBackingIndex(drops: List<LootDrop>, stackIndex: Int): Int? =
        backingIndexForStack(drops, stackIndex, ::lootDropsMatch)

    fun lootDropsMatch(a: LootDrop, b: LootDrop): Boolean = when {
        a is LootDrop.IngredientDrop && b is LootDrop.IngredientDrop ->
            a.ingredient == b.ingredient
        a is LootDrop.MeleeWeaponDrop && b is LootDrop.MeleeWeaponDrop ->
            a.weapon == b.weapon && a.tier == b.tier
        a is LootDrop.FloorKeyDrop && b is LootDrop.FloorKeyDrop ->
            a.key == b.key
        a is LootDrop.ArmorDrop && b is LootDrop.ArmorDrop ->
            a.armorName == b.armorName
        else -> false
    }
}
