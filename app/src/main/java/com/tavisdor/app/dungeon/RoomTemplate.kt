package com.tavisdor.app.dungeon

/**
 * One pre-authored dungeon chunk loaded by [TemplateLibrary] from a PNG under
 * `assets/dungeon/rooms/`. The author paints on an RGB canvas:
 *
 *   - White  (#FFFFFF) -> void, ignored.
 *   - Black  (#000000) -> walkable floor cell, internal to the room.
 *   - Red    (#FF0000) -> walkable floor cell AND a connector that may merge
 *                         with another template's connector during stitching.
 *   - Green  (#00FF00) -> walkable floor cell AND a door. Door lock state is
 *                         rolled per-instance at placement time; locked doors
 *                         halt auto-move and open a 3-option prompt
 *                         (use key / pick lock / force open).
 *   - Blue   (#0000FF) -> walkable floor cell AND a staircase DOWN to the
 *                         next deeper floor.
 *   - Yellow (#FFFF00) -> walkable floor cell AND a staircase UP to the
 *                         previous floor. Treated symmetrically to blue: at
 *                         most one per floor and gated by the same spawn
 *                         logic so the player can backtrack.
 *
 * All non-void pixels become floor cells; the connector/door/staircase sets
 * are sub-classifications layered on top of [floorCells]. Coordinates are
 * template-local (0,0)-based, with the bounding box of painted pixels
 * translated to the origin so authoring-canvas padding doesn't leak in.
 *
 * Stitching rule (see [FloorGenerator] / [Floor.tryPlaceTemplate]): two
 * templates may overlap only on cells that are connectors in BOTH; any
 * other overlap is a collision.
 */
data class RoomTemplate(
    val id: String,
    val width: Int,
    val height: Int,
    val floorCells: Set<Cell>,
    val connectors: Set<Cell>,
    val doors: Set<Cell> = emptySet(),
    val staircases: Set<Cell> = emptySet(),
    val stairsUp: Set<Cell> = emptySet(),
    /**
     * True when this template represents a boss room (filename starts
     * with `boss`). Boss templates are gated by [Floor.bossTemplateAllowed]
     * - eligible only on even-depth floors and at most once per floor.
     */
    val isBoss: Boolean = false,
    /**
     * True when this template is one of the dungeon's entrance pieces
     * (filename starts with `start_`). Used by [FloorGenerator] to seed
     * the spawn room; intentionally excluded from the filler / stair
     * pool so a start tile only appears at the start.
     */
    val isStart: Boolean = false,
    /**
     * True when this template is one of the stairs-down "exit" pieces
     * (filename starts with `end_`). Gated by
     * [Floor.staircaseTemplateAllowed] - only spawnable after the
     * exploration threshold opens, and intentionally excluded from
     * filler spawns so the stairs-down room is always an end tile.
     */
    val isEnd: Boolean = false,
    /**
     * True when this template is a "special" event room
     * (filename starts with `sp_`). Gated by
     * [Floor.specialTemplateAllowed]: at most one special room per
     * floor, regardless of depth - the player should never see two
     * `sp_*` rooms on the same level.
     */
    val isSpecial: Boolean = false,
    /**
     * True when this template is a hallway segment
     * (filename starts with `hall_`). Hallways link rooms together
     * and do NOT count toward the floor's room budget; the generator
     * enforces a 1-to-3 hallway chain between any two rooms via the
     * per-connector hall-depth counter on [Floor].
     */
    val isHall: Boolean = false,
    /**
     * True when this template is a destination room
     * (filename starts with `room_`). Counted against the floor's
     * room budget along with start / end / sp / boss tiles. Each
     * placed [isRoom] tile resets the chain depth of its newly
     * exposed connectors back to 0 so the next leg starts with a
     * hallway.
     *
     * Unprefixed legacy `tile*.png` files are also classified here
     * as a backward-compat fallback while filenames are being
     * migrated; once the source assets are fully renamed, the
     * fallback can be retired.
     */
    val isRoom: Boolean = false,
    /**
     * True when this template is a hallway end-cap
     * (filename starts with `end_room_`). Placed by [FloorGenerator]
     * AFTER the room budget is met, to seal any still-open
     * connectors so the dungeon ships as a fully closed layout with
     * no runtime tap-to-extend. Each cap is one-connector by
     * authoring convention; the cap with the matching connector
     * orientation lines up against the open red pixel naturally
     * through the standard merge-on-red rule.
     */
    val isEndCap: Boolean = false,
) {
    /**
     * Count of "black" floor cells - i.e. plain walkable tiles that
     * are NOT connectors, doors, or stairs. Drives the enemy-spawn
     * capacity tiers in [com.tavisdor.app.dungeon.EnemySpawner]:
     *
     *   - <= 9  -> capacity 1
     *   - 10-20 -> capacity 3
     *   - >= 21 -> capacity 4
     *
     * Hallways further require black >= 4 to spawn anything.
     *
     * Computed once at construction by subtracting every "special"
     * pixel set from [floorCells]. Safe against overlap because the
     * sets are unioned before subtracting.
     */
    val blackPixelCount: Int = run {
        val nonBlack = HashSet<Cell>(connectors.size + doors.size + staircases.size + stairsUp.size)
        nonBlack.addAll(connectors)
        nonBlack.addAll(doors)
        nonBlack.addAll(staircases)
        nonBlack.addAll(stairsUp)
        floorCells.count { it !in nonBlack }
    }

    /**
     * The walkable cells eligible to host enemy spawns: floor cells
     * minus every special pixel (connectors / doors / stairs).
     * Lazily computed - only [EnemySpawner] reads this today.
     */
    val nonSpecialFloorCells: Set<Cell> by lazy(LazyThreadSafetyMode.NONE) {
        val nonBlack = HashSet<Cell>(connectors.size + doors.size + staircases.size + stairsUp.size)
        nonBlack.addAll(connectors)
        nonBlack.addAll(doors)
        nonBlack.addAll(staircases)
        nonBlack.addAll(stairsUp)
        floorCells.filterTo(HashSet(floorCells.size - nonBlack.size)) { it !in nonBlack }
    }

    init {
        require(width > 0 && height > 0) { "Template $id has empty bounding box." }
        require(connectors.all { it in floorCells }) {
            "Template $id has a connector that is not in floorCells."
        }
        require(doors.all { it in floorCells }) {
            "Template $id has a door that is not in floorCells."
        }
        require(staircases.all { it in floorCells }) {
            "Template $id has a staircase that is not in floorCells."
        }
        require(stairsUp.all { it in floorCells }) {
            "Template $id has a stairs-up that is not in floorCells."
        }
    }

    /** Returns the bounding-box footprint translated by [offset], for placement checks. */
    fun footprintAt(offset: Cell): Set<Cell> =
        floorCells.mapTo(HashSet(floorCells.size)) { Cell(it.x + offset.x, it.y + offset.y) }

    /** Returns the connectors translated by [offset]. */
    fun connectorsAt(offset: Cell): Set<Cell> =
        connectors.mapTo(HashSet(connectors.size)) { Cell(it.x + offset.x, it.y + offset.y) }

    /**
     * Cell where the party should spawn on this template: stairs up (return
     * path) if present, else stairs down, else a stable floor tile fallback.
     */
    fun preferredPartyStartCell(): Cell {
        val order = compareBy<Cell>({ it.y }, { it.x })
        return stairsUp.minWithOrNull(order)
            ?: staircases.minWithOrNull(order)
            ?: floorCells.minWithOrNull(order)
            ?: Cell(0, 0)
    }

    /**
     * Debug helper. Returns the template as ASCII so a logcat dump can be
     * eyeballed against the source PNG.
     *
     *   `#` = floor, `R` = connector, `D` = door, `S` = stairs down,
     *   `U` = stairs up, `.` = void.
     */
    fun toAsciiGrid(): String {
        val sb = StringBuilder()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = Cell(x, y)
                sb.append(
                    when {
                        c in staircases -> 'S'
                        c in stairsUp -> 'U'
                        c in doors -> 'D'
                        c in connectors -> 'R'
                        c in floorCells -> '#'
                        else -> '.'
                    }
                )
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}
