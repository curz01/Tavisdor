package com.tavisdor.app.dungeon

/**
 * Shortest-path solver over the dungeon grid.
 *
 * The dungeon has uniform per-step cost (one cell = one move) and no
 * weights, so a plain breadth-first search is optimal and noticeably
 * cheaper than A*. We only do 4-directional moves to keep step animations
 * aligned with the cardinal-only tap rule used elsewhere.
 *
 * Used by [com.tavisdor.app.game.Game.requestMoveTo] when the player taps
 * a non-adjacent floor cell during exploration: the returned path is fed
 * to the per-frame mover one cell at a time so the party visibly walks
 * to the destination.
 */
object Pathfinder {

    private val CARDINAL_NEIGHBORS = arrayOf(
        Cell(0, -1),
        Cell(0, 1),
        Cell(-1, 0),
        Cell(1, 0),
    )

    /**
     * Returns the cells the party walks through, INCLUDING [from] at index 0
     * and [to] at the last index. Returns an empty list when [to] is not
     * walkable or not reachable from [from]; returns `[from]` when
     * `from == to`.
     */
    fun findPath(floor: Floor, from: Cell, to: Cell): List<Cell> {
        if (from == to) return listOf(from)
        if (to !in floor.floorCells) return emptyList()
        if (from !in floor.floorCells) return emptyList()

        val cameFrom = HashMap<Cell, Cell>()
        val visited = HashSet<Cell>()
        val frontier: ArrayDeque<Cell> = ArrayDeque()

        frontier.addLast(from)
        visited += from

        var found = false
        while (frontier.isNotEmpty()) {
            val curr = frontier.removeFirst()
            if (curr == to) {
                found = true
                break
            }
            for (dir in CARDINAL_NEIGHBORS) {
                val n = Cell(curr.x + dir.x, curr.y + dir.y)
                if (n in visited) continue
                if (n !in floor.floorCells) continue
                visited += n
                cameFrom[n] = curr
                frontier.addLast(n)
            }
        }
        if (!found) return emptyList()

        // Reconstruct from `to` back to `from`, then reverse.
        val reverse = ArrayList<Cell>()
        var curr = to
        while (curr != from) {
            reverse += curr
            curr = cameFrom[curr] ?: return emptyList()
        }
        reverse += from
        reverse.reverse()
        return reverse
    }
}
