package com.tavisdor.app.save

import com.tavisdor.app.items.Ingredient
import com.tavisdor.app.items.Potion
import com.tavisdor.app.items.LootTier
import com.tavisdor.app.items.WeaponType
import com.tavisdor.app.party.Gender
import com.tavisdor.app.party.HeroClass

/**
 * Snapshot of game state written to persistent storage. Floor layout is
 * captured only as the seed used to generate it - on resume, [com.tavisdor.app.dungeon.FloorGenerator]
 * is re-run with that seed to reconstruct the same dungeon.
 *
 * Auto-saved at the start of each floor. Manual "Save & Quit" mid-floor will
 * also write here, with extra fields added later (party cell, fog mask,
 * monster HP per room) once those subsystems exist.
 */
data class SaveData(
    val schemaVersion: Int,
    val heroes: List<HeroSaveData>,
    val currentFloor: Int,
    val floorSeed: Long,
    val partyGold: Int = 0,
    val inventoryWeapons: List<WeaponSaveData> = emptyList(),
    val inventoryIngredients: List<Ingredient> = emptyList(),
    val inventoryPotions: List<Potion> = emptyList(),
) {
    companion object {
        /**
         * Bump whenever the on-disk shape of a save changes.
         *  v1 - initial scaffold (class, level, xp, maxHp, hp, dexterity).
         *  v2 - adds [HeroSaveData.name].
         *  v3 - adds [HeroSaveData.gender]. v1 / v2 saves default to MALE
         *       so the portrait renderer still has a sprite set to draw.
         *  v4 - adds [SaveData.partyGold], [SaveData.inventoryWeapons],
         *       and [SaveData.inventoryIngredients]. Older saves default
         *       to 0 gold and an empty inventory.
         *  v5 - adds [SaveData.inventoryPotions].
         */
        const val CURRENT_SCHEMA = 5
    }
}

data class HeroSaveData(
    val name: String,
    val heroClass: HeroClass,
    val gender: Gender,
    val level: Int,
    val xp: Int,
    val maxHp: Int,
    val hp: Int,
    val dexterity: Int,
)

/**
 * Serializable record of one inventory weapon. The runtime
 * [com.tavisdor.app.items.Weapon] is reconstructed from
 * (type, tier, attackBonus, range) - the display name is recomposed
 * from the tier so a future rename of [LootTier.meleePrefix] doesn't
 * pin saved weapons to the old wording.
 *
 * [tier] is nullable to preserve the "crude starter" sentinel even
 * though crude weapons live on heroes today, not the bag - keeps
 * the format symmetric with [com.tavisdor.app.items.Weapon].
 */
data class WeaponSaveData(
    val type: WeaponType,
    val tier: LootTier?,
    val attackBonus: Int,
    val range: Int,
)
