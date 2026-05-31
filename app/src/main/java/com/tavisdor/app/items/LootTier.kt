package com.tavisdor.app.items

/**
 * The 10-row loot progression table indexed by dungeon depth. Each
 * tier names the material / quality prefix used to construct items
 * for each of the four loot categories:
 *
 *   - [meleePrefix]: material prefix on weapons and armor; also sets
 *                    base attack via [meleeWeaponBaseDamage] (Attack +1
 *                    … +10 on the chart). Suffixes are separate —
 *                    see [SuffixRoller] and [ItemDisplayNames].
 *   - [orbPrefix]:   prepended to "Orb" for the gem mounted on a
 *                    staff (e.g. "Marble Orb"). Drops independent of
 *                    the staff; combat math treats it as a separate
 *                    equipment slot.
 *   - [armorName]:   legacy drop label; maps to [ArmorType] via
 *                    [ArmorType.fromLootTier] for stats and slots.
 *   - [bowTierName]: bow quality tier, suffixed by " Bow" except
 *                    when the tier name already contains it
 *                    ("Longbow"). See [displayBowName].
 *
 * Tier ranges are right-inclusive and contiguous from depth 1 to
 * 40. Depths beyond 40 clamp to [T37_40] - there's nothing deeper
 * authored, but the player shouldn't suddenly stop seeing loot if
 * they grind past the table.
 *
 * Add a new enum entry to extend; do not renumber existing entries
 * because saved games (when loot persistence lands) may reference
 * tiers by ordinal.
 */
enum class LootTier(
    val minDepth: Int,
    val maxDepth: Int,
    val meleePrefix: String,
    val orbPrefix: String,
    val armorName: String,
    val bowTierName: String,
) {
    T1_3(    1,  3, "Wood",          "Limestone", "Cloth",            "Simple"),
    T4_8(    4,  8, "Bone",          "Basalt",    "Leather",          "Short"),
    T9_12(   9, 12, "Copper",        "Granite",   "Studded Leather",  "Recurve"),
    T13_16( 13, 16, "Bronze",        "Marble",    "Chainmail",        "Longbow"),
    T17_20( 17, 20, "Iron",          "Obsidian",  "Scale Armor",      "Composite"),
    T21_24( 21, 24, "Steel",         "Quartz",    "Iron Plate",       "Reinforced"),
    T25_28( 25, 28, "Elven Steel",   "Amethyst",  "Steel Plate",      "Elven"),
    T29_32( 29, 32, "Mithril",       "Sapphire",  "Mithril Armor",    "Mithril"),
    T33_36( 33, 36, "Runite",        "Opal",      "Dragonhide Armor", "Dragonbone"),
    T37_40( 37, 40, "Divine Crystal","Diamond",   "Celestial Armor",  "Celestial"),
    ;

    /**
     * Bow display name. Returns the tier name unchanged when it
     * already contains "Bow" (e.g. "Longbow"); otherwise appends
     * " Bow" so the player sees "Composite Bow" rather than just
     * "Composite".
     */
    fun displayBowName(plusLevel: Int = 0): String {
        val base =
            if (bowTierName.contains("bow", ignoreCase = true)) bowTierName
            else "$bowTierName Bow"
        return if (plusLevel > 0) "$base +$plusLevel" else base
    }

    /** Composes "Wood Spear" / "Wood Spear +1" / "Mithril Staff +2" etc. */
    fun displayMeleeName(weapon: WeaponType, plusLevel: Int = 0): String {
        val base = "$meleePrefix ${weapon.displayName}"
        return if (plusLevel > 0) "$base +$plusLevel" else base
    }

    /** Melee attack bonus for this tier plus an optional enchant ([plusLevel]). */
    fun meleeWeaponAttackBonus(plusLevel: Int = 0): Int = meleeWeaponBaseDamage + plusLevel

    /** Composes "Limestone Orb" / "Diamond Orb" / etc. */
    fun displayOrbName(): String = "$orbPrefix Orb"

    /**
     * Base damage contributed by a melee weapon of this tier before
     * STR / skill modifiers are added. Linear scaling against the
     * enum ordinal until per-weapon damage values are authored:
     *
     *   T1_3 (Wood) = 1, T4_8 = 2, T9_12 = 3, ..., T37_40 = 10.
     *
     * Combat math reads this via `weapon.tier.meleeWeaponBaseDamage`
     * when a weapon is equipped, and falls back to FISTS_DAMAGE
     * otherwise.
     */
    val meleeWeaponBaseDamage: Int get() = ordinal + 1

    companion object {
        /**
         * Damage contributed by an "unarmed" attack when no melee
         * weapon is equipped. Floored at 1 so even a barehanded
         * Mage still threatens a Spear Goblin.
         */
        const val FISTS_DAMAGE: Int = 1

        /**
         * Returns the loot tier covering [depth]. Below tier 1
         * clamps to [T1_3] (shouldn't happen - depth always >= 1);
         * above tier 10 clamps to [T37_40] so post-cap dungeons
         * keep dropping max-tier loot.
         */
        fun forDepth(depth: Int): LootTier {
            if (depth <= T1_3.minDepth) return T1_3
            entries.forEach { if (depth in it.minDepth..it.maxDepth) return it }
            return T37_40
        }
    }
}
