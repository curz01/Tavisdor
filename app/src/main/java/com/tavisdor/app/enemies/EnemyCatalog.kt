package com.tavisdor.app.enemies

import com.tavisdor.app.items.WeaponType
import kotlin.random.Random

/**
 * Central registry of every authored [EnemyTemplate], keyed by
 * [EnemyTemplate.id]. The single source of truth that
 * [FloorGenerator] (eventually) and combat code go through to look
 * up enemy data.
 *
 * Adding a new enemy:
 *   1. Append an [EnemyTemplate] entry to [AUTHORED] below.
 *   2. Sprite / loot wiring happens in their respective systems;
 *      this catalog only needs the stats.
 */
object EnemyCatalog {

    /**
     * Author every enemy here. Order doesn't matter; the [id] field
     * is the only thing callers look up by.
     *
     * Keep entries grouped by intended depth (Floor 1 enemies
     * together, etc.) for readability - the runtime doesn't care,
     * but it makes the file easier to scan when balancing.
     */
    private val AUTHORED: List<EnemyTemplate> = listOf(
        // ----- Floor 1 -----
        EnemyTemplate(
            id = "spear_goblin",
            name = "Spear Goblin",
            level = 1,
            spawnWeight = 2,
            element = Element.NEUTRAL,
            movementSquares = 1,
            weaponType = WeaponType.SPEAR,
            combatClass = EnemyCombatClass.FIGHTER,
            strength = 3,
            dexterity = 2,
            intelligence = 1,
            // Max HP = Enemy.BASE_MAX_HP (2) + 2*STR = 8 at STR 3.
            armorClass = 1,
            awardedExperience = 50,
            // Loot: see LootTableCatalog "spear_goblin" (ingredients, shards,
            // 40% melee weapon, 10% +1 melee weapon).
            lootTableId = "spear_goblin",
            goldMin = 5,
            goldMax = 15,
            // Portrait for the top-of-screen turn-order strip.
            portraitAsset = "sprites/speargob_port.png",
            // Two-frame idle alternation on the dungeon grid; the
            // renderer cycles 1 -> 2 -> 1 every `walkFrameDurationMs`.
            walkSpriteAssets = listOf(
                "sprites/spear_gob1.png",
                "sprites/spear_gob2.png",
            ),
        ),
        EnemyTemplate(
            id = "bow_goblin",
            name = "Bow Goblin",
            level = 1,
            spawnWeight = 1,
            element = Element.NEUTRAL,
            movementSquares = 1,
            attackRange = 2,
            weaponType = WeaponType.BOW,
            combatClass = EnemyCombatClass.ARCHER,
            strength = 1,
            dexterity = 3,
            intelligence = 2,
            armorClass = 1,
            awardedExperience = 60,
            lootTableId = "bow_goblin",
            goldMin = 10,
            goldMax = 20,
            portraitAsset = "sprites/archergob_port.png",
            walkSpriteAssets = listOf(
                "sprites/arch_gob1.png",
                "sprites/arch_gob2.png",
            ),
        ),
        EnemyTemplate(
            id = "bat",
            name = "Bat",
            level = 1,
            // Same pool weight as bow_goblin (spear_goblin remains 2).
            spawnWeight = 1,
            element = Element.AIR,
            movementSquares = 3,
            moveThenStrikeSameTurn = true,
            weaponType = WeaponType.BITE,
            combatClass = EnemyCombatClass.BEAST,
            strength = 2,
            dexterity = 2,
            intelligence = 0,
            armorClass = 1,
            awardedExperience = 50,
            lootTableId = "bat",
            goldMin = 0,
            goldMax = 0,
            portraitAsset = "sprites/bat_port.png",
            restWalkSpriteAssets = listOf(
                "sprites/batrest.png",
                "sprites/batrest2.png",
            ),
            walkSpriteAssets = listOf(
                "sprites/bat1.png",
                "sprites/bat2.png",
            ),
        ),
    )

    private val byId: Map<String, EnemyTemplate> =
        AUTHORED.associateBy { it.id }.also { map ->
            check(map.size == AUTHORED.size) {
                val dupes = AUTHORED.groupBy { it.id }.filter { it.value.size > 1 }.keys
                "Duplicate enemy id(s) in EnemyCatalog.AUTHORED: $dupes"
            }
        }

    /** Look up a template by [id]. Returns null when the id isn't authored. */
    fun get(id: String): EnemyTemplate? = byId[id]

    /**
     * Look up a template by [id], throwing with a clear message if
     * it doesn't exist. Use this at known-safe call sites (e.g.
     * loading a save the catalog just authored).
     */
    fun require(id: String): EnemyTemplate =
        byId[id] ?: error("No EnemyTemplate authored for id='$id'.")

    /** Every authored template, in insertion order. Useful for bestiary UIs. */
    fun all(): List<EnemyTemplate> = AUTHORED

    /**
     * Every authored template at the given [level]. Convenience for
     * "spawn something appropriate" queries while real floor-gating
     * doesn't exist yet.
     */
    fun atLevel(level: Int): List<EnemyTemplate> = AUTHORED.filter { it.level == level }

    /**
     * Picks one template from [candidates] using each entry's
     * [EnemyTemplate.spawnWeight] (higher = more common).
     */
    fun pickWeighted(candidates: List<EnemyTemplate>, rng: Random): EnemyTemplate {
        require(candidates.isNotEmpty())
        val total = candidates.sumOf { it.spawnWeight }
        var roll = rng.nextInt(total)
        for (template in candidates) {
            roll -= template.spawnWeight
            if (roll < 0) return template
        }
        return candidates.last()
    }
}
