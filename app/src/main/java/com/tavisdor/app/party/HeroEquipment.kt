package com.tavisdor.app.party

import com.tavisdor.app.items.Inventory
import com.tavisdor.app.items.Weapon

/**
 * Equip / unequip helpers for the inventory overlay. Weapon moves
 * between [Inventory.weapons] (Gear tab) and [Hero.weapon1] /
 * [Hero.weapon2].
 */
object HeroEquipment {

    enum class WeaponSlot {
        PRIMARY,
        OFF_HAND,
    }

    enum class EquipResult {
        SUCCESS,
        NOT_IN_INVENTORY,
        NOT_USABLE_BY_HERO,
    }

    fun equipWeapon(
        party: Party,
        heroIndex: Int,
        slot: WeaponSlot,
        weapon: Weapon,
    ): EquipResult {
        val hero = party.heroes.getOrNull(heroIndex) ?: return EquipResult.NOT_IN_INVENTORY
        if (!weapon.type.canBeUsedBy(hero.heroClass)) return EquipResult.NOT_USABLE_BY_HERO

        val inv = party.inventory
        val fromBag = inv.removeFirstWeapon(weapon) ?: return EquipResult.NOT_IN_INVENTORY
        val previous = when (slot) {
            WeaponSlot.PRIMARY -> hero.weapon1
            WeaponSlot.OFF_HAND -> hero.weapon2
        }
        if (previous != null && !inv.addWeapon(previous)) {
            inv.addWeapon(fromBag)
            return EquipResult.NOT_IN_INVENTORY
        }

        val updated = when (slot) {
            WeaponSlot.PRIMARY -> hero.copy(weapon1 = fromBag)
            WeaponSlot.OFF_HAND -> hero.copy(weapon2 = fromBag)
        }
        party.replaceHero(heroIndex, updated)
        return EquipResult.SUCCESS
    }

    /** Moves the equipped weapon into [Inventory.weapons]. Returns false if the slot was empty. */
    fun unequipWeapon(party: Party, heroIndex: Int, slot: WeaponSlot): Boolean {
        val hero = party.heroes.getOrNull(heroIndex) ?: return false
        val weapon = when (slot) {
            WeaponSlot.PRIMARY -> hero.weapon1
            WeaponSlot.OFF_HAND -> hero.weapon2
        } ?: return false

        if (!party.inventory.addWeapon(weapon)) return false
        val updated = when (slot) {
            WeaponSlot.PRIMARY -> hero.copy(weapon1 = null)
            WeaponSlot.OFF_HAND -> hero.copy(weapon2 = null)
        }
        party.replaceHero(heroIndex, updated)
        return true
    }
}
