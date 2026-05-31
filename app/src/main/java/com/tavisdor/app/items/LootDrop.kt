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
     * A weapon of [weapon] type at the dungeon's current [tier]
     * (melee, [WeaponType.BOW], or [WeaponType.STAFF]).
     * [plusLevel] adds that much to the tier base attack
     * (e.g. Wood +1 Spear = 2 attack; Simple Bow +1 = 2 attack).
     */
    data class MeleeWeaponDrop(
        val weapon: WeaponType,
        val tier: LootTier,
        val plusLevel: Int = 0,
        val suffixes: List<ItemSuffix> = emptyList(),
    ) : LootDrop() {
        fun displayName(): String =
            ItemDisplayNames.composeWeapon(tier, weapon, suffixes, plusLevel)
    }

    /** Dropped by an enemy assigned to guard a specific floor lock. */
    data class FloorKeyDrop(val key: FloorKey) : LootDrop()

    /** Random armor piece — deposited into the Equipment tab stash. */
    data class ArmorDrop(
        val type: ArmorType,
        val slot: ArmorPieceSlot,
        val plusLevel: Int = 0,
        val suffixes: List<ItemSuffix> = emptyList(),
    ) : LootDrop() {
        val armorName: String
            get() = ItemDisplayNames.composeArmor(type, slot, suffixes, plusLevel)
    }
}
