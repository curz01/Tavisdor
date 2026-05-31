package com.tavisdor.app.combat

import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.dungeon.Floor
import kotlin.math.abs

/**
 * Range + line-of-sight checks used by the combat controller when
 * validating ranged attacks (arrows, fireballs, earth spells).
 *
 * Range is measured in Manhattan steps to stay consistent with the
 * rest of the game's grid math (movement, disengage adjacency,
 * enemy AI pathfinding). A cell at delta `(dx, dy)` is in range R
 * iff `|dx| + |dy| <= R`.
 *
 * Line-of-sight uses Bresenham's algorithm to walk the straight
 * line from origin to target; every cell along the way is
 * inspected and the line fails as soon as it hits a wall (any
 * cell not in [Floor.floorCells]) or any door cell ([Floor.isDoor]).
 * Doors always block LOS so unrevealed rooms stay hidden in combat
 * even after the lock is picked; the party must enter to see inside.
 *
 * Enemies are intentionally NOT treated as LOS blockers for now:
 * blocking on enemies would make ranged backline plays
 * unworkable when even one front-line enemy stood between the
 * party and the target. Revisit if "shooting over heads" feels
 * too generous.
 *
 * All checks are pure; the helper has no state and is safe to
 * call from anywhere in the combat tick.
 */
object LineOfSight {

    /**
     * Manhattan distance between [a] and [b]. Re-exported here so
     * callers don't have to duplicate the same one-liner that's
     * scattered across [CombatController] for adjacency probes.
     */
    fun manhattan(a: Cell, b: Cell): Int = abs(a.x - b.x) + abs(a.y - b.y)

    /** True iff [target] sits within [range] Manhattan cells of [origin]. */
    fun isInRange(origin: Cell, target: Cell, range: Int): Boolean =
        manhattan(origin, target) <= range

    /**
     * Grid cells on the straight line from [from] to [to], including
     * both endpoints (Bresenham). Used by enemy positioning AI.
     */
    fun lineCells(from: Cell, to: Cell): List<Cell> = bresenhamLine(from, to)

    /**
     * True when [blocker] sits on the interior of the line between
     * [from] and [to] (not on either endpoint).
     */
    fun blocksLineBetween(blocker: Cell, from: Cell, to: Cell): Boolean {
        val line = lineCells(from, to)
        if (line.size <= 2) return false
        return blocker in line.subList(1, line.size - 1)
    }

    /**
     * True iff a straight line from [origin] to [target] passes
     * through only walkable cells (floor + open doors). Both
     * endpoints are excluded from the check - the attacker stands
     * on [origin] and the target sits on [target], neither blocks
     * itself.
     *
     * Returns true immediately when [origin] == [target] (no line
     * to walk) and when the two cells are adjacent (Manhattan 1):
     * there's nothing between them to obstruct.
     *
     * All door cells block LOS; regular floor cells let the line pass.
     */
    fun hasLineOfSight(floor: Floor, origin: Cell, target: Cell): Boolean {
        if (origin == target) return true
        val md = manhattan(origin, target)
        if (md <= 1) return true

        val line = bresenhamLine(origin, target)
        // The first cell is [origin], the last is [target]; only
        // the interior cells can obstruct the line.
        for (i in 1 until line.size - 1) {
            val cell = line[i]
            if (!floor.isFloor(cell)) return false
            if (floor.isDoor(cell)) return false
        }
        return true
    }

    /**
     * Standard Bresenham line rasterizer from [from] to [to].
     * Returns the ordered sequence of integer grid cells the line
     * passes through, including both endpoints.
     *
     * Used here for LOS sampling - we don't draw anything, we
     * just need each cell on the line to query [Floor] state.
     */
    private fun bresenhamLine(from: Cell, to: Cell): List<Cell> {
        val cells = ArrayList<Cell>(manhattan(from, to) + 1)
        var x = from.x
        var y = from.y
        val dx = abs(to.x - from.x)
        val dy = abs(to.y - from.y)
        val sx = if (from.x < to.x) 1 else -1
        val sy = if (from.y < to.y) 1 else -1
        var err = dx - dy

        while (true) {
            cells += Cell(x, y)
            if (x == to.x && y == to.y) break
            val e2 = 2 * err
            if (e2 > -dy) {
                err -= dy
                x += sx
            }
            if (e2 < dx) {
                err += dx
                y += sy
            }
        }
        return cells
    }
}
