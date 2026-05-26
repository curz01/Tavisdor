package com.tavisdor.app.items

import com.tavisdor.app.party.HeroClass

/**
 * The eight authored weapon types. Each carries:
 *
 *   - [displayName]: human-readable name shown in tooltips and loot
 *                    descriptions. Combines with [LootTier.meleePrefix]
 *                    (e.g. "Bronze" + "Spear" = "Bronze Spear") for
 *                    melee weapons via [LootTier.displayMeleeName],
 *                    or with [LootTier.bowTierName] for bows.
 *   - [usableBy]:    the set of classes that can equip this weapon.
 *                    Equip / wield checks should always go through
 *                    [canBeUsedBy] rather than reading the set
 *                    directly, so future overrides (cursed gear,
 *                    multiclass perks, story unlocks) only need to
 *                    touch one method.
 *   - [reach]:       MELEE for adjacent-square attacks, RANGED for
 *                    bows. Used by combat to pick the to-hit
 *                    resolver and by inventory to choose the right
 *                    paper-doll slot. STAFF still counts as MELEE
 *                    because its base is swung like a club; the
 *                    Orb mounted on it is what enables ranged
 *                    spell casts (see [hasOrbSlot]).
 *
 * Class restrictions come straight from the design chart - keep
 * them in sync when the chart changes.
 */
enum class WeaponType(
    val displayName: String,
    val usableBy: Set<HeroClass>,
    val reach: Reach,
) {
    SPEAR(  "Spear",  setOf(HeroClass.FIGHTER, HeroClass.MAGE), Reach.MELEE),
    SWORD(  "Sword",  setOf(HeroClass.FIGHTER, HeroClass.THIEF), Reach.MELEE),
    DAGGER( "Dagger", setOf(HeroClass.FIGHTER, HeroClass.THIEF, HeroClass.ARCHER), Reach.MELEE),
    AXE(    "Axe",    setOf(HeroClass.FIGHTER, HeroClass.ARCHER), Reach.MELEE),
    HAMMER( "Hammer", setOf(HeroClass.FIGHTER, HeroClass.MAGE), Reach.MELEE),
    MACE(   "Mace",   setOf(HeroClass.FIGHTER), Reach.MELEE),
    BOW(    "Bow",    setOf(HeroClass.ARCHER, HeroClass.THIEF), Reach.RANGED),
    STAFF(  "Staff",  setOf(HeroClass.MAGE, HeroClass.FIGHTER), Reach.MELEE),
    ;

    /** Whether [cls] is permitted to equip this weapon type. */
    fun canBeUsedBy(cls: HeroClass): Boolean = cls in usableBy

    /**
     * True iff this weapon hosts an Orb (see [LootTier.orbPrefix]).
     * Only [STAFF] does today; centralised here so combat / equip
     * code never has to enum-compare directly.
     */
    val hasOrbSlot: Boolean get() = this == STAFF

    enum class Reach { MELEE, RANGED }

    companion object {
        /** Every weapon type [cls] can wield, in enum-declaration order. */
        fun allFor(cls: HeroClass): List<WeaponType> = entries.filter { it.canBeUsedBy(cls) }

        /** Every melee-reach weapon type (used by the goblin's loot table). */
        val MELEE_TYPES: List<WeaponType> = entries.filter { it.reach == Reach.MELEE }

        /** Every ranged-reach weapon type. */
        val RANGED_TYPES: List<WeaponType> = entries.filter { it.reach == Reach.RANGED }
    }
}
