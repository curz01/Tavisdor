package com.tavisdor.app.items

import com.tavisdor.app.party.HeroClass

/**
 * One physical armor piece: a [type] material in a specific [slot].
 * [acBonus] comes from [ArmorType.acBonus]. Suffixes are rolled at drop
 * time and do not change AC unless a future suffix says so.
 */
data class ArmorItem(
    val type: ArmorType,
    val slot: ArmorPieceSlot,
    val suffixes: List<ItemSuffix> = emptyList(),
    val plusLevel: Int = 0,
) {
    val acBonus: Int get() = type.acBonus

    val displayName: String
        get() = ItemDisplayNames.composeArmor(type, slot, suffixes, plusLevel)

    fun canBeEquippedBy(cls: HeroClass): Boolean =
        ArmorType.canEquip(cls, type, slot)

    companion object {
        fun fromLootTier(
            tier: LootTier,
            slot: ArmorPieceSlot = ArmorPieceSlot.ARMOR,
            suffixes: List<ItemSuffix> = emptyList(),
            plusLevel: Int = 0,
        ): ArmorItem {
            val type = ArmorType.fromLootTier(tier)
            val resolvedSlot = if (slot in type.slots) slot else ArmorPieceSlot.ARMOR
            return ArmorItem(type, resolvedSlot, suffixes, plusLevel)
        }

        fun fromLegacyDisplayName(name: String): ArmorItem {
            val plus = Regex("""\+(\d+)$""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val resolved = ArmorType.resolveFromLegacyName(name)
            if (resolved != null) {
                return ArmorItem(resolved.first, resolved.second, plusLevel = plus)
            }
            return fromLootTier(LootTier.T1_3, plusLevel = plus)
        }
    }
}
