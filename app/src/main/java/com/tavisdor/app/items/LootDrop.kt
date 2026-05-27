package com.tavisdor.app.items

/**
 * One concrete item that came out of a successful loot roll. The
 * [LootTable] returns a list of these per kill (possibly empty).
 *
 * Sealed so callers (inventory write-back, combat-end UI summary)
 * can exhaustively switch on the variant. Add new payload subtypes
 * here as the loot system grows; today only the drop categories
 * actually authored on an enemy are modeled.
 */
sealed class LootDrop {
    /** A random ingredient at the rolled potency tier. */
    data class IngredientDrop(val ingredient: Ingredient) : LootDrop()

    /**
     * A melee weapon of [weapon] type at the dungeon's current
     * [tier]. The combined display name is
     * `tier.displayMeleeName(weapon)` (e.g. "Wood Spear").
     */
    data class MeleeWeaponDrop(val weapon: WeaponType, val tier: LootTier) : LootDrop()

    /** Dropped by an enemy assigned to guard a specific floor lock. */
    data class FloorKeyDrop(val key: FloorKey) : LootDrop()
}
