package com.tavisdor.app.items

import com.tavisdor.app.party.HeroClass

/**
 * Authored armor materials: who can wear them, which piece slots exist
 * for that material, and the AC each piece contributes. No per-class
 * specials yet — only [acBonus] and restrictions.
 *
 * Depth drops map [LootTier] → an [ArmorType] via [fromLootTier]; mage-only
 * [HEAVY_CLOAK] and [ROYAL_ROBE] exist for future loot, not tier tables.
 */
enum class ArmorType(
    val displayName: String,
    val usableBy: Set<HeroClass>,
    val slots: Set<ArmorPieceSlot>,
    /** AC added per equipped piece of this material. */
    val acBonus: Int,
) {
    CLOTH(
        "Cloth",
        setOf(HeroClass.FIGHTER, HeroClass.THIEF, HeroClass.MAGE, HeroClass.ARCHER),
        setOf(ArmorPieceSlot.ARMOR, ArmorPieceSlot.HELMET, ArmorPieceSlot.BOOTS),
        1,
    ),
    LEATHER(
        "Leather",
        setOf(HeroClass.FIGHTER, HeroClass.THIEF, HeroClass.ARCHER),
        setOf(ArmorPieceSlot.ARMOR, ArmorPieceSlot.SHIELD, ArmorPieceSlot.HELMET, ArmorPieceSlot.BOOTS),
        2,
    ),
    STUDDED_LEATHER(
        "Studded Leather",
        setOf(HeroClass.FIGHTER, HeroClass.THIEF, HeroClass.ARCHER),
        setOf(ArmorPieceSlot.ARMOR, ArmorPieceSlot.SHIELD, ArmorPieceSlot.HELMET, ArmorPieceSlot.BOOTS),
        3,
    ),
    CHAINMAIL(
        "Chainmail",
        setOf(HeroClass.FIGHTER, HeroClass.THIEF),
        setOf(ArmorPieceSlot.ARMOR, ArmorPieceSlot.HELMET, ArmorPieceSlot.BOOTS),
        4,
    ),
    HEAVY_CLOAK(
        "Heavy Cloak",
        setOf(HeroClass.MAGE),
        setOf(ArmorPieceSlot.ARMOR),
        2,
    ),
    SCALE(
        "Scale",
        setOf(HeroClass.FIGHTER, HeroClass.THIEF, HeroClass.ARCHER),
        setOf(ArmorPieceSlot.ARMOR, ArmorPieceSlot.SHIELD, ArmorPieceSlot.HELMET, ArmorPieceSlot.BOOTS),
        4,
    ),
    IRON_PLATE(
        "Iron Plate",
        setOf(HeroClass.FIGHTER),
        setOf(ArmorPieceSlot.ARMOR, ArmorPieceSlot.SHIELD, ArmorPieceSlot.HELMET, ArmorPieceSlot.BOOTS),
        5,
    ),
    STEEL_PLATE(
        "Steel Plate",
        setOf(HeroClass.FIGHTER),
        setOf(ArmorPieceSlot.ARMOR, ArmorPieceSlot.SHIELD, ArmorPieceSlot.HELMET, ArmorPieceSlot.BOOTS),
        7,
    ),
    MITHRIL(
        "Mithril",
        setOf(HeroClass.FIGHTER, HeroClass.THIEF),
        setOf(ArmorPieceSlot.ARMOR, ArmorPieceSlot.SHIELD, ArmorPieceSlot.HELMET, ArmorPieceSlot.BOOTS),
        5,
    ),
    ROYAL_ROBE(
        "Royal Robe",
        setOf(HeroClass.MAGE),
        setOf(ArmorPieceSlot.ARMOR),
        3,
    ),
    DRAGONHIDE(
        "Dragonhide",
        setOf(HeroClass.THIEF, HeroClass.ARCHER),
        setOf(ArmorPieceSlot.ARMOR, ArmorPieceSlot.HELMET, ArmorPieceSlot.BOOTS),
        6,
    ),
    CELESTIAL(
        "Celestial",
        setOf(HeroClass.FIGHTER),
        setOf(ArmorPieceSlot.ARMOR, ArmorPieceSlot.SHIELD, ArmorPieceSlot.HELMET, ArmorPieceSlot.BOOTS),
        9,
    ),
    ;

    fun canBeUsedBy(cls: HeroClass): Boolean = cls in usableBy

    fun usableByLabel(): String =
        usableBy.joinToString(", ") { WeaponClassRules.classDisplayName(it) }

    fun slotsLabel(): String = ArmorPieceSlot.chartCodes(slots)

    /** Display name for one [slot] of this material, e.g. "Leather Helmet". */
    fun pieceName(slot: ArmorPieceSlot): String = when (slot) {
        ArmorPieceSlot.ARMOR -> when (this) {
            HEAVY_CLOAK -> "Heavy Cloak"
            ROYAL_ROBE -> "Royal Robe"
            IRON_PLATE, STEEL_PLATE -> "$displayName"
            else -> displayName
        }
        else -> "$displayName ${slot.displayName}"
    }

    companion object {
        /** Only fighters may equip shield-slot items. */
        const val SHIELD_FIGHTER_ONLY: Boolean = true

        fun fromLootTier(tier: LootTier): ArmorType = when (tier) {
            LootTier.T1_3 -> CLOTH
            LootTier.T4_8 -> LEATHER
            LootTier.T9_12 -> STUDDED_LEATHER
            LootTier.T13_16 -> CHAINMAIL
            LootTier.T17_20 -> SCALE
            LootTier.T21_24 -> IRON_PLATE
            LootTier.T25_28 -> STEEL_PLATE
            LootTier.T29_32 -> MITHRIL
            LootTier.T33_36 -> DRAGONHIDE
            LootTier.T37_40 -> CELESTIAL
        }

        fun canEquip(cls: HeroClass, type: ArmorType, slot: ArmorPieceSlot): Boolean {
            if (!type.canBeUsedBy(cls)) return false
            if (slot !in type.slots) return false
            if (SHIELD_FIGHTER_ONLY && slot == ArmorPieceSlot.SHIELD && cls != HeroClass.FIGHTER) {
                return false
            }
            return true
        }

        /** Reference text for the inventory equipment tab. */
        fun armorChartText(): String = buildString {
            append("Shields: Fighter only.\n\n")
            entries.forEach { type ->
                append(type.displayName)
                append(" — ")
                append(type.usableByLabel())
                append('\n')
                append("  Pieces: ")
                append(type.slotsLabel())
                append(" (")
                append(type.slots.joinToString(", ") { it.displayName })
                append(")\n")
                append("  AC: +")
                append(type.acBonus)
                append(" per piece\n\n")
            }
        }.trimEnd()

        fun resolveFromLegacyName(name: String): Pair<ArmorType, ArmorPieceSlot>? {
            val core = name.replace(Regex("""\s*\+\d+$"""), "").trim()
            val slot = ArmorPieceSlot.entries.firstOrNull { core.endsWith(it.displayName) }
            val typeName = if (slot != null) {
                core.removeSuffix(slot.displayName).trim()
            } else {
                core
            }
            val type = entries.firstOrNull { t ->
                typeName.equals(t.displayName, ignoreCase = true) ||
                    typeName.equals(t.pieceName(ArmorPieceSlot.ARMOR), ignoreCase = true) ||
                    core.contains(t.displayName, ignoreCase = true)
            } ?: LootTier.entries
                .filter { core.startsWith(it.armorName) || core.contains(it.armorName) }
                .maxByOrNull { it.armorName.length }
                ?.let { fromLootTier(it) }
                ?: return null
            val resolvedSlot = slot ?: ArmorPieceSlot.ARMOR
            if (resolvedSlot !in type.slots) return null
            return type to resolvedSlot
        }
    }
}
