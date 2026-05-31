package com.tavisdor.app.items

import kotlin.random.Random

/** Builds concrete [Weapon] / [ArmorItem] from loot drops and depth. */
object LootGearFactory {

    fun rollMeleeWeaponDrop(
        weapon: WeaponType,
        tier: LootTier,
        plusLevel: Int,
        depth: Int,
        rng: Random,
    ): LootDrop.MeleeWeaponDrop {
        val suffixes = SuffixRoller.rollSuffixes(depth, rng)
        return LootDrop.MeleeWeaponDrop(weapon, tier, plusLevel, suffixes)
    }

    fun rollArmorDrop(
        tier: LootTier,
        plusLevel: Int,
        depth: Int,
        rng: Random,
    ): LootDrop.ArmorDrop {
        val suffixes = SuffixRoller.rollSuffixes(depth, rng)
        val type = ArmorType.fromLootTier(tier)
        val slot = rollArmorSlot(type, rng)
        return LootDrop.ArmorDrop(type, slot, plusLevel, suffixes)
    }

    /** Picks a random piece slot this material supports (for loot variety). */
    private fun rollArmorSlot(type: ArmorType, rng: Random): ArmorPieceSlot {
        val options = type.slots.toList()
        return options[rng.nextInt(options.size)]
    }

    fun weaponFromDrop(drop: LootDrop.MeleeWeaponDrop): Weapon {
        val type = drop.weapon
        val tier = drop.tier
        val plus = drop.plusLevel.coerceAtLeast(0)
        val attackBonus = tier.meleeWeaponAttackBonus(plus)
        val displayName = ItemDisplayNames.composeWeapon(tier, type, drop.suffixes, plus)
        return when (type) {
            WeaponType.BOW -> Weapon(
                type = type,
                tier = tier,
                displayName = displayName,
                attackBonus = attackBonus,
                range = Weapon.CRUDE_BOW_RANGE,
                plusLevel = plus,
                suffixes = drop.suffixes,
            )
            else -> Weapon(
                type = type,
                tier = tier,
                displayName = displayName,
                attackBonus = attackBonus,
                range = Weapon.CRUDE_MELEE_RANGE,
                plusLevel = plus,
                suffixes = drop.suffixes,
            )
        }
    }

    fun armorFromDrop(drop: LootDrop.ArmorDrop): ArmorItem =
        ArmorItem(drop.type, drop.slot, drop.suffixes, drop.plusLevel)
}
