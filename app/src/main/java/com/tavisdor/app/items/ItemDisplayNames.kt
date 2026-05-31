package com.tavisdor.app.items

/**
 * Composes player-facing equipment names:
 *
 *   `(prefix) (type) (suffix) (suffix) …` — e.g.
 *   `Mithril Sword of Mighty Hasty Element of Skills`
 *
 * Prefix = [LootTier.meleePrefix] (+ attack). Suffixes append after the
 * type in roll order (each is either `Mighty` or `of Might`).
 */
object ItemDisplayNames {

    fun composeWeapon(
        tier: LootTier,
        weapon: WeaponType,
        suffixes: List<ItemSuffix>,
        plusLevel: Int = 0,
    ): String = when (weapon) {
        WeaponType.BOW -> composeRanged(tier.displayBowName(0), suffixes, plusLevel)
        else -> composeMelee(tier.displayMeleeName(weapon, 0), suffixes, plusLevel)
    }

    fun composeArmor(
        type: ArmorType,
        slot: ArmorPieceSlot,
        suffixes: List<ItemSuffix>,
        plusLevel: Int = 0,
    ): String = composeMelee(type.pieceName(slot), suffixes, plusLevel)

    private fun composeMelee(
        baseName: String,
        suffixes: List<ItemSuffix>,
        plusLevel: Int,
    ): String {
        val core = buildString {
            append(baseName)
            if (suffixes.isNotEmpty()) {
                append(' ')
                append(suffixes.joinToString(" ") { it.displayFragment() })
            }
        }
        return if (plusLevel > 0) "$core +$plusLevel" else core
    }

    private fun composeRanged(
        baseName: String,
        suffixes: List<ItemSuffix>,
        plusLevel: Int,
    ): String = composeMelee(baseName, suffixes, plusLevel)
}
