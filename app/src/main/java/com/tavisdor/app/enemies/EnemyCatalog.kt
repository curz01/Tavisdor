package com.tavisdor.app.enemies

import com.tavisdor.app.items.WeaponType

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
            element = Element.NEUTRAL,
            movementSquares = 1,
            weaponType = WeaponType.SPEAR,
            strength = 3,
            dexterity = 2,
            intelligence = 1,
            // Squishy Floor-1 fodder. A L1 Fighter swinging fists
            // (STR 5 + 1) clears 5 damage on a hit; the Goblin's
            // HP 10 pool means a clean 2-shot kill before weapons
            // even drop. Bump this if early floors play too soft.
            armorClass = 1,
            // baseMaxHp / baseMaxMp aren't on the design chart yet.
            // Picked so derived totals land at Max HP = 10 (4 + 2*STR)
            // and Max MP = 3 (0 + 3*INT). Update once the chart adds them.
            baseMaxHp = 4,
            baseMaxMp = 0,
            awardedExperience = 50,
            // Loot table description (from chart, parsed when loot system lands):
            //   50% chance to drop a Level 1 random ingredient,
            //   10% to drop a melee weapon.
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
}
