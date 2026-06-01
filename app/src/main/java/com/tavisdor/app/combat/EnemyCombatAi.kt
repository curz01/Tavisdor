package com.tavisdor.app.combat

import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.dungeon.Floor
import com.tavisdor.app.dungeon.Pathfinder
import com.tavisdor.app.enemies.Enemy
import com.tavisdor.app.items.WeaponType

/**
 * Tactical movement for goblin packs: bow users seek open shots at
 * range first; when they can shoot they may reposition farther or
 * diagonally from the party to reduce melee pressure. Spear users
 * clear friendly-fire lanes and body-block for archers. Melee
 * closers press toward the party when they cannot strike yet.
 */
object EnemyCombatAi {

    /**
     * Cells [enemy] will step through this turn (not including its
     * start tile). Empty when the enemy should stay put.
     */
    fun planMovePath(
        floor: Floor,
        enemy: Enemy,
        allEnemies: List<Enemy>,
        partyCell: Cell,
    ): List<Cell> {
        val budget = enemy.movementSquares
        if (budget <= 0) return emptyList()
        if (enemy.cell == partyCell) return emptyList()
        if (!enemy.template.moveThenStrikeSameTurn &&
            enemy.weaponType != WeaponType.BOW &&
            canStrikeFrom(floor, enemy, enemy.cell, partyCell)
        ) {
            return emptyList()
        }

        val blocked = otherEnemyCells(enemy, allEnemies)
        val tactical = when (enemy.weaponType) {
            WeaponType.SPEAR -> planSpearTactics(floor, enemy, allEnemies, partyCell, blocked, budget)
            WeaponType.BOW -> planBowTactics(floor, enemy, partyCell, blocked, budget)
            WeaponType.BITE -> planBatTactics(floor, enemy, partyCell, blocked, budget)
            else -> null
        }
        if (tactical != null) {
            if (tactical.isNotEmpty() || !prefersAggressiveMeleeApproach(enemy)) {
                return tactical
            }
        }
        return planAggressiveMeleeApproach(floor, enemy, partyCell, blocked, budget)
    }

    // ----- Spear goblin: clear bow lanes, then guard archers -----

    private fun planSpearTactics(
        floor: Floor,
        spear: Enemy,
        allEnemies: List<Enemy>,
        partyCell: Cell,
        blocked: Set<Cell>,
        budget: Int,
    ): List<Cell>? {
        val bows = allEnemies.filter { it.isAlive && it.weaponType == WeaponType.BOW }
        if (bows.isEmpty()) return null

        for (bow in bows) {
            if (!LineOfSight.blocksLineBetween(spear.cell, bow.cell, partyCell)) continue
            val path = planStepOffArcherLine(floor, spear, bow, partyCell, blocked, budget)
            if (path.isNotEmpty()) return path
        }

        for (bow in bows) {
            if (!LineOfSight.hasLineOfSight(floor, partyCell, bow.cell)) continue
            if (LineOfSight.blocksLineBetween(spear.cell, partyCell, bow.cell)) {
                return null
            }
            val path = planGuardArcher(floor, spear, bow, partyCell, blocked, budget)
            if (path.isNotEmpty()) return path
        }

        return null
    }

    /** Move to a nearby cell that no longer blocks the bow's shot. */
    private fun planStepOffArcherLine(
        floor: Floor,
        spear: Enemy,
        bow: Enemy,
        partyCell: Cell,
        blocked: Set<Cell>,
        budget: Int,
    ): List<Cell> {
        val reachable = cellsWithinSteps(floor, spear.cell, blocked, budget)
        val candidates = reachable.filter { end ->
            end != spear.cell &&
                !LineOfSight.blocksLineBetween(end, bow.cell, partyCell)
        }
        if (candidates.isEmpty()) return emptyList()
        val best = candidates.minWith(
            compareBy<Cell> { manhattan(it, partyCell) }
                .thenBy { stepsToReach(floor, spear.cell, it, blocked) },
        )
        return stepsAlongPath(floor, spear.cell, best, blocked, budget)
    }

    /** Step onto the party–bow line to block the party's line of fire. */
    private fun planGuardArcher(
        floor: Floor,
        spear: Enemy,
        bow: Enemy,
        partyCell: Cell,
        blocked: Set<Cell>,
        budget: Int,
    ): List<Cell> {
        val line = LineOfSight.lineCells(partyCell, bow.cell)
        if (line.size <= 2) return emptyList()
        val guardSpots = line.subList(1, line.size - 1)
            .filter { it in floor.floorCells && !floor.isLockedDoor(it) && it != bow.cell }
        if (guardSpots.isEmpty()) return emptyList()

        val reachable = cellsWithinSteps(floor, spear.cell, blocked, budget)
        val candidates = guardSpots.filter { it in reachable }
        if (candidates.isEmpty()) return emptyList()

        val best = candidates.minBy { stepsToReach(floor, spear.cell, it, blocked) }
        return stepsAlongPath(floor, spear.cell, best, blocked, budget)
    }

    // ----- Bow goblin: damage first, then kite / diagonal when safe -----

    private fun planBowTactics(
        floor: Floor,
        bow: Enemy,
        partyCell: Cell,
        blocked: Set<Cell>,
        budget: Int,
    ): List<Cell> {
        val reachable = cellsWithinSteps(floor, bow.cell, blocked, budget)
        val strikeCells = reachable.filter { canStrikeFrom(floor, bow, it, partyCell) }
        if (strikeCells.isEmpty()) {
            return planApproachForShot(floor, bow, partyCell, blocked, budget)
        }

        val best = strikeCells.maxWith(bowPositionComparator(bow.cell, partyCell, blocked, floor))
        if (best == bow.cell) return emptyList()
        return stepsAlongPath(floor, bow.cell, best, blocked, budget)
    }

    /**
     * Walk toward a shot when none is available yet. Damage over
     * position: closest in-range LOS tile wins; only then prefer
     * farther / less melee-exposed cells among ties.
     */
    private fun planApproachForShot(
        floor: Floor,
        bow: Enemy,
        partyCell: Cell,
        blocked: Set<Cell>,
        budget: Int,
    ): List<Cell> {
        val reachable = cellsWithinSteps(floor, bow.cell, blocked, budget)
        val range = bow.attackRange
        val candidates = reachable.filter { cell ->
            LineOfSight.isInRange(cell, partyCell, range) &&
                LineOfSight.hasLineOfSight(floor, cell, partyCell)
        }
        if (candidates.isEmpty()) {
            return planAggressiveMeleeApproach(floor, bow, partyCell, blocked, budget)
        }
        val best = candidates.minWith(
            compareBy<Cell> { manhattan(it, partyCell) }
                .thenBy { partyMeleePressure(it, partyCell) }
                .thenBy { stepsToReach(floor, bow.cell, it, blocked) },
        )
        if (best == bow.cell) return emptyList()
        return stepsAlongPath(floor, bow.cell, best, blocked, budget)
    }

    /**
     * Among tiles that still allow a shot this turn, prefer the
     * farthest from the party, then tiles that are harder for
     * cardinal-only melee to reach (diagonal adjacency).
     */
    private fun bowPositionComparator(
        from: Cell,
        partyCell: Cell,
        blocked: Set<Cell>,
        floor: Floor,
    ): Comparator<Cell> =
        compareBy<Cell> { manhattan(it, partyCell) }
            .thenBy { partyMeleePressure(it, partyCell) }
            .thenByDescending { stepsToReach(floor, from, it, blocked) }

    /**
     * How exposed [cell] is to party melee from [partyCell]. Lower is
     * better for a ranged enemy. Cardinal adjacency is worst; diagonal
     * adjacency avoids heroes with range-1 cardinal melee; two or more
     * steps away is safest.
     */
    private fun partyMeleePressure(cell: Cell, partyCell: Cell): Int {
        val chebyshev = LineOfSight.chebyshev(cell, partyCell)
        if (chebyshev >= 2) return 0
        return if (manhattan(cell, partyCell) == 1) 2 else 1
    }

    // ----- Bat: close distance, then bite on the same turn -----

    private fun planBatTactics(
        floor: Floor,
        enemy: Enemy,
        partyCell: Cell,
        blocked: Set<Cell>,
        budget: Int,
    ): List<Cell> {
        if (canStrikeFrom(floor, enemy, enemy.cell, partyCell)) return emptyList()
        return planAggressiveMeleeApproach(floor, enemy, partyCell, blocked, budget)
    }

    // ----- Default: press toward the party while unable to strike -----

    /** Non-ranged enemies that should close distance even when blocked. */
    private fun prefersAggressiveMeleeApproach(enemy: Enemy): Boolean =
        enemy.weaponType != WeaponType.BOW

    /**
     * Spend the full movement budget getting as close to [partyCell]
     * as pathing allows. Unlike the old chase, we do not stop on the
     * first tile that could strike — if the party is still blocked,
     * keep closing so a freed square puts them nearer.
     */
    private fun planAggressiveMeleeApproach(
        floor: Floor,
        enemy: Enemy,
        partyCell: Cell,
        blocked: Set<Cell>,
        budget: Int,
    ): List<Cell> {
        val path = Pathfinder.findPath(floor, enemy.cell, partyCell, blocked)
        if (path.size > 1) {
            val cells = ArrayList<Cell>(budget)
            var stepsTaken = 0
            var i = 1
            while (i < path.size && stepsTaken < budget) {
                val next = path[i]
                if (next == partyCell) break
                cells += next
                stepsTaken += 1
                i += 1
            }
            if (cells.isNotEmpty()) return cells
        }
        return planClosestApproach(floor, enemy, partyCell, blocked, budget)
    }

    /**
     * When the party cell is unreachable (allies / doors in the way),
     * step toward the reachable tile with the smallest distance to
     * the party.
     */
    private fun planClosestApproach(
        floor: Floor,
        enemy: Enemy,
        partyCell: Cell,
        blocked: Set<Cell>,
        budget: Int,
    ): List<Cell> {
        val reachable = cellsWithinSteps(floor, enemy.cell, blocked, budget)
        val target = reachable
            .filter { it != enemy.cell }
            .minWith(
                compareBy<Cell> { manhattan(it, partyCell) }
                    .thenBy { stepsToReach(floor, enemy.cell, it, blocked) },
            )
            ?: return emptyList()
        if (target == enemy.cell) return emptyList()
        return stepsAlongPath(floor, enemy.cell, target, blocked, budget)
    }

    // ----- Shared helpers -----

    private fun canStrikeFrom(floor: Floor, enemy: Enemy, from: Cell, partyCell: Cell): Boolean {
        if (!LineOfSight.isInRange(from, partyCell, enemy.attackRange)) return false
        return LineOfSight.hasLineOfSight(floor, from, partyCell)
    }

    private fun otherEnemyCells(self: Enemy, all: List<Enemy>): Set<Cell> {
        val out = HashSet<Cell>()
        for (e in all) {
            if (e === self || !e.isAlive) continue
            out += e.cell
        }
        return out
    }

    private fun cellsWithinSteps(
        floor: Floor,
        start: Cell,
        blocked: Set<Cell>,
        maxSteps: Int,
    ): Set<Cell> {
        val seen = HashSet<Cell>()
        val queue = ArrayDeque<Pair<Cell, Int>>()
        queue.addLast(start to 0)
        seen += start
        while (queue.isNotEmpty()) {
            val (cur, depth) = queue.removeFirst()
            if (depth >= maxSteps) continue
            for (delta in CARDINAL) {
                val n = Cell(cur.x + delta.x, cur.y + delta.y)
                if (n in seen) continue
                if (n !in floor.floorCells) continue
                if (floor.isLockedDoor(n)) continue
                if (n in blocked) continue
                seen += n
                queue.addLast(n to depth + 1)
            }
        }
        return seen
    }

    private fun stepsToReach(
        floor: Floor,
        from: Cell,
        to: Cell,
        blocked: Set<Cell>,
    ): Int {
        val path = Pathfinder.findPath(floor, from, to, blocked)
        return (path.size - 1).coerceAtLeast(0)
    }

    private fun stepsAlongPath(
        floor: Floor,
        from: Cell,
        to: Cell,
        blocked: Set<Cell>,
        budget: Int,
    ): List<Cell> {
        val path = Pathfinder.findPath(floor, from, to, blocked)
        if (path.size <= 1) return emptyList()
        return path.drop(1).take(budget)
    }

    private fun manhattan(a: Cell, b: Cell): Int = LineOfSight.manhattan(a, b)

    private val CARDINAL = arrayOf(
        Cell(0, -1),
        Cell(0, 1),
        Cell(-1, 0),
        Cell(1, 0),
    )
}
