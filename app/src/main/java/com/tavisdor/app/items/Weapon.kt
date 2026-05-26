package com.tavisdor.app.items

import com.tavisdor.app.party.HeroClass

/**
 * Concrete, equippable weapon. Bridges the abstract [WeaponType]
 * (sword / staff / bow / ...) and a quality tier (the
 * [LootTier]-derived "Wood / Bronze / Mithril / ..." prefix or
 * the special "Crude" starter grade).
 *
 * Combat math reads two things off the weapon:
 *   - [attackBonus]: replaces [LootTier.FISTS_DAMAGE] in the
 *     melee formula (STR + weapon + skill.damage). A weapon
 *     whose [attackBonus] is 0 makes the hero hit for STR alone -
 *     intentional for the "crude" starter tier so the early game
 *     stays low-stakes.
 *   - [range]: feeds [com.tavisdor.app.combat.LineOfSight.isInRange]
 *     for basic attacks. Bows let the archer engage from a
 *     distance; melee weapons all cap at 1 (touching).
 *
 * Crude starters do not belong to a [LootTier] (they're below
 * the chart's T1_3 floor), so [tier] is nullable. Looted
 * weapons that drop from enemies will set a tier; that hook is
 * here for when [LootTable.RandomMeleeWeapon] starts producing
 * concrete `Weapon` instances instead of raw [LootDrop]s.
 */
data class Weapon(
    val type: WeaponType,
    val tier: LootTier?,
    val displayName: String,
    val attackBonus: Int,
    val range: Int,
) {
    /** Convenience flag for combat code: bows / future ranged-types. */
    val isRanged: Boolean get() = type.reach == WeaponType.Reach.RANGED

    companion object {
        /**
         * Range a crude starter bow grants. Set to 2 deliberately:
         * one cell more than melee so the archer feels distinct
         * from the start, but enough headroom for later bow tiers
         * to scale up to 3 / 4 without making the starter feel
         * already-maxed.
         */
        const val CRUDE_BOW_RANGE: Int = 2

        /**
         * Range for crude melee weapons (sword / dagger / staff).
         * Matches the default basic-attack range so the math here
         * is symmetric: equipping a crude melee weapon doesn't
         * change reach, only attackBonus (which is 0 anyway -
         * crude weapons trade away the [LootTier.FISTS_DAMAGE]
         * bonus that bare fists give).
         */
        const val CRUDE_MELEE_RANGE: Int = 1

        /**
         * Damage bonus crude starters add on top of STR. Zero by
         * design: starting weapons are flavor + reach, not raw
         * power. Looted weapons set this from
         * [LootTier.meleeWeaponBaseDamage] (T1_3 = 1, ..., T37_40 = 10).
         */
        const val CRUDE_ATTACK_BONUS: Int = 0

        /**
         * The starting weapon every hero of [cls] spawns with.
         * Class -> weapon mapping is hand-authored:
         *   - FIGHTER -> Crude Sword (range 1)
         *   - THIEF   -> Crude Dagger (range 1)
         *   - MAGE    -> Crude Staff (range 1)
         *   - ARCHER  -> Crude Bow (range 2, the only ranged starter)
         *
         * All four return weapons with [attackBonus] = 0 so the
         * baseline damage drops to pure STR until proper loot
         * lands. This is intentional - the starter is more about
         * giving the archer reach than buffing damage.
         */
        fun crudeStarterFor(cls: HeroClass): Weapon {
            val type = when (cls) {
                HeroClass.FIGHTER -> WeaponType.SWORD
                HeroClass.THIEF -> WeaponType.DAGGER
                HeroClass.MAGE -> WeaponType.STAFF
                HeroClass.ARCHER -> WeaponType.BOW
            }
            val range = if (type.reach == WeaponType.Reach.RANGED) {
                CRUDE_BOW_RANGE
            } else {
                CRUDE_MELEE_RANGE
            }
            return Weapon(
                type = type,
                tier = null,
                displayName = "Crude ${type.displayName}",
                attackBonus = CRUDE_ATTACK_BONUS,
                range = range,
            )
        }
    }
}
