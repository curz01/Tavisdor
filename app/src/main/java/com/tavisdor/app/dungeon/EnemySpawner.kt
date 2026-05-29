package com.tavisdor.app.dungeon

import com.tavisdor.app.enemies.Enemy
import com.tavisdor.app.enemies.EnemyCatalog
import com.tavisdor.app.enemies.EnemyTemplate
import kotlin.random.Random

/**
 * Rolls and places enemies inside one freshly-placed [RoomTemplate].
 *
 * Decision flow:
 *   1. Filter: start tiles, boss tiles, and end-caps spawn nothing.
 *      Hallways with < [HALLWAY_MIN_BLACK_PIXELS] black cells also
 *      spawn nothing.
 *   2. Capacity: pick the room's max enemy count from black-pixel
 *      count (see [capacityFor]). Hallways cap at 1.
 *   3. Count roll (see [rollSpawnCount]):
 *        10%  -> 0 enemies
 *        30%  -> 1 enemy
 *        30%  -> 2 enemies
 *        20%  -> 3 enemies
 *        10%  -> capacity (max)
 *      Clamped to capacity, so small rooms / hallways with capacity
 *      1 see the upper buckets collapse into a single enemy.
 *   4. Cell pick: uniformly random distinct cells from
 *      [RoomTemplate.nonSpecialFloorCells], translated by [offset].
 *   5. Enemy template pick: prefer an exact level == [floorDepth]
 *      match in [EnemyCatalog]; fall back to the deepest authored
 *      template with `level <= floorDepth`. Returns empty when no
 *      template is authored for this depth or shallower.
 *
 * Pure - just returns the proposed enemies. Caller registers them
 * onto [Floor] (see [Floor.commitTemplate]).
 */
object EnemySpawner {

    // ----- Capacity table -----

    /** Inclusive upper bound for "small" rooms. */
    const val SMALL_ROOM_MAX_BLACK: Int = 9

    /** Inclusive upper bound for "medium" rooms; everything above is large. */
    const val MEDIUM_ROOM_MAX_BLACK: Int = 20

    const val SMALL_ROOM_CAPACITY: Int = 1
    const val MEDIUM_ROOM_CAPACITY: Int = 3
    const val LARGE_ROOM_CAPACITY: Int = 4

    /** Hallways below this black-pixel floor spawn nothing at all. */
    const val HALLWAY_MIN_BLACK_PIXELS: Int = 4

    const val HALLWAY_CAPACITY: Int = 1

    // ----- Probability bucket boundaries (cumulative %, exclusive upper) -----
    // Roll a value in [0, 100); the bucket the roll lands in determines
    // the proposed count BEFORE the capacity clamp.

    private const val EMPTY_UP_TO: Int = 10            // [0, 10)   -> 0 enemies
    private const val ONE_ENEMY_UP_TO: Int = 40        // [10, 40)  -> 1 enemy
    private const val TWO_ENEMIES_UP_TO: Int = 70      // [40, 70)  -> 2 enemies
    private const val THREE_ENEMIES_UP_TO: Int = 90    // [70, 90)  -> 3 enemies
    // [90, 100) -> capacity (max).

    /**
     * Top-level entry point. Returns the enemies to place inside
     * [template] at world-space [offset], rolling against [rng].
     *
     * Empty list = no enemies (either by rule, by 10% empty roll,
     * or because no authored [EnemyCatalog] template is available
     * at or below [floorDepth]).
     */
    fun spawnFor(
        template: RoomTemplate,
        offset: Cell,
        floorDepth: Int,
        rng: Random,
    ): List<Enemy> {
        if (!isSpawnEligible(template)) return emptyList()

        val capacity = capacityFor(template)
        if (capacity == 0) return emptyList()

        val count = rollSpawnCount(rng, capacity)
        if (count == 0) return emptyList()

        val candidatesLocal = template.nonSpecialFloorCells
        if (candidatesLocal.isEmpty()) return emptyList()

        val candidatesWorld = candidatesLocal.map { Cell(it.x + offset.x, it.y + offset.y) }
        // shuffled(rng).take(n) gives n distinct cells without replacement.
        val chosenCells = candidatesWorld.shuffled(rng).take(count)

        val enemyTemplate = pickEnemyTemplate(floorDepth, rng) ?: return emptyList()
        return chosenCells.map { Enemy.spawnAt(enemyTemplate, it) }
    }

    /**
     * Places [count] enemies for a camp ambush at distinct [cells] using
     * the standard depth-appropriate template pick (one template per ambush).
     */
    fun spawnCampAmbush(
        cells: List<Cell>,
        floorDepth: Int,
        rng: Random,
    ): List<Enemy> {
        if (cells.isEmpty()) return emptyList()
        val enemyTemplate = pickEnemyTemplate(floorDepth, rng) ?: return emptyList()
        return cells.map { Enemy.spawnAt(enemyTemplate, it) }
    }

    // ----- Eligibility / capacity -----

    /**
     * Templates that never spawn enemies regardless of capacity:
     *   - Start tile: the party spawns here, so it's a safe respawn
     *     point on a wipe (full-HP refill location).
     *   - Boss tile: gated on per-floor boss data which isn't
     *     authored yet (one boss creature per even floor coming).
     *   - End-cap: dead-end seal placed after the room budget is
     *     hit; treating these as empty caps keeps the dungeon
     *     readable as a closed layout without surprise dead-end
     *     ambushes.
     */
    private fun isSpawnEligible(template: RoomTemplate): Boolean =
        !template.isStart && !template.isBoss && !template.isEndCap

    /**
     * Resolves the per-template enemy capacity.
     *   - Hallways: 1 if black >= [HALLWAY_MIN_BLACK_PIXELS], else 0.
     *   - Rooms: tiered by black-pixel count (small / medium / large).
     */
    private fun capacityFor(template: RoomTemplate): Int {
        val black = template.blackPixelCount
        if (template.isHall) {
            return if (black >= HALLWAY_MIN_BLACK_PIXELS) HALLWAY_CAPACITY else 0
        }
        return when {
            black <= SMALL_ROOM_MAX_BLACK -> SMALL_ROOM_CAPACITY
            black <= MEDIUM_ROOM_MAX_BLACK -> MEDIUM_ROOM_CAPACITY
            else -> LARGE_ROOM_CAPACITY
        }
    }

    /**
     * Rolls a 0..99 value and maps it to the proposed enemy count,
     * then clamps to [capacity]. Visible for testing; production
     * calls go through [spawnFor].
     */
    internal fun rollSpawnCount(rng: Random, capacity: Int): Int {
        val roll = rng.nextInt(0, 100)
        val raw = when {
            roll < EMPTY_UP_TO -> 0
            roll < ONE_ENEMY_UP_TO -> 1
            roll < TWO_ENEMIES_UP_TO -> 2
            roll < THREE_ENEMIES_UP_TO -> 3
            else -> capacity
        }
        return raw.coerceAtMost(capacity)
    }

    // ----- Enemy template selection -----

    /**
     * Picks an enemy template appropriate for [floorDepth]:
     *   - First choice: any [EnemyCatalog] entry whose `level`
     *     matches [floorDepth] exactly.
     *   - Fallback: the entries with the deepest `level <= floorDepth`.
     *     Lets Floor 5 keep spawning Floor-4 enemies while Floor-5
     *     monsters haven't been authored yet.
     * Returns null when no authored entry has `level <= floorDepth`.
     */
    private fun pickEnemyTemplate(floorDepth: Int, rng: Random): EnemyTemplate? {
        val exact = EnemyCatalog.atLevel(floorDepth)
        if (exact.isNotEmpty()) return exact.random(rng)
        val belowOrEqual = EnemyCatalog.all().filter { it.level <= floorDepth }
        if (belowOrEqual.isEmpty()) return null
        val deepestLevel = belowOrEqual.maxOf { it.level }
        return belowOrEqual.filter { it.level == deepestLevel }.random(rng)
    }
}
