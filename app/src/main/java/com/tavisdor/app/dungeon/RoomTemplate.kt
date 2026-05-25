package com.tavisdor.app.dungeon

/**
 * One pre-authored dungeon chunk loaded by [TemplateLibrary] from a PNG under
 * `assets/dungeon/rooms/`. The author paints on an RGB canvas:
 *
 *   - White (#FFFFFF) -> void, ignored.
 *   - Black (#000000) -> walkable floor cell, internal to the room.
 *   - Red   (#FF0000) -> walkable floor cell AND a connector that may merge
 *                        with another template's connector during stitching.
 *   - Green (#00FF00) -> walkable floor cell AND a door. Door lock state is
 *                        rolled per-instance at placement time; locked doors
 *                        halt auto-move and open a 3-option prompt
 *                        (use key / pick lock / force open).
 *   - Blue  (#0000FF) -> walkable floor cell AND a staircase to the next
 *                        deeper floor.
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
    /**
     * True when this template represents a boss room (filename starts
     * with `boss`). Boss templates are gated by [Floor.bossTemplateAllowed]
     * - eligible only on even-depth floors and at most once per floor.
     */
    val isBoss: Boolean = false,
) {
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
    }

    /** Returns the bounding-box footprint translated by [offset], for placement checks. */
    fun footprintAt(offset: Cell): Set<Cell> =
        floorCells.mapTo(HashSet(floorCells.size)) { Cell(it.x + offset.x, it.y + offset.y) }

    /** Returns the connectors translated by [offset]. */
    fun connectorsAt(offset: Cell): Set<Cell> =
        connectors.mapTo(HashSet(connectors.size)) { Cell(it.x + offset.x, it.y + offset.y) }

    /**
     * Debug helper. Returns the template as ASCII so a logcat dump can be
     * eyeballed against the source PNG.
     *
     *   `#` = floor, `R` = connector, `D` = door, `S` = staircase, `.` = void.
     */
    fun toAsciiGrid(): String {
        val sb = StringBuilder()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val c = Cell(x, y)
                sb.append(
                    when {
                        c in staircases -> 'S'
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
