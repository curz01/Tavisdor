package com.tavisdor.app.save

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
) {
    companion object {
        /**
         * Bump whenever the on-disk shape of a save changes.
         *  v1 - initial scaffold (class, level, xp, maxHp, hp, dexterity).
         *  v2 - adds [HeroSaveData.name].
         */
        const val CURRENT_SCHEMA = 2
    }
}

data class HeroSaveData(
    val name: String,
    val heroClass: HeroClass,
    val level: Int,
    val xp: Int,
    val maxHp: Int,
    val hp: Int,
    val dexterity: Int,
)
