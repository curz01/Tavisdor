package com.tavisdor.app.dungeon

import kotlin.random.Random

/**
 * One generated dungeon level. All cell coordinates are dungeon-global and
 * may be negative if the floor grows toward the upper-left; the renderer
 * camera-transforms them, so no rebasing is required.
 *
 * Floor state mutates at runtime: when the player taps an open connector,
 * [tryExtendFromConnector] tries to attach a new template at that boundary,
 * adding new cells (and possibly new doors / staircases / open connectors).
 *
 * Created by [FloorGenerator] from a (`depth`, `seed`) pair. The seed is
 * retained so the initial layout can be regenerated on resume from save;
 * player-driven extensions and per-door lock state are not yet persisted
 * (see save-schema TODO).
 */
class Floor(
    val depth: Int,
    val seed: Long,
    initialFloorCells: Set<Cell>,
    initialOpenConnectors: Set<Cell>,
    initialPlacedConnectors: Set<Cell>,
    initialDoors: Map<Cell, Door>,
    initialStaircases: Set<Cell>,
    initialPartyCell: Cell,
) {
    // Internal mutable state; exposed read-only to the rest of the app so
    // mutation only happens through the controlled methods below.
    private val _floorCells: HashSet<Cell> = HashSet(initialFloorCells)
    private val _openConnectors: HashSet<Cell> = HashSet(initialOpenConnectors)
    private val _placedConnectors: HashSet<Cell> = HashSet(initialPlacedConnectors)
    private val _doors: HashMap<Cell, Door> = HashMap(initialDoors)
    private val _staircases: HashSet<Cell> = HashSet(initialStaircases)

    /**
     * Cells the party has actually stepped on. Seeded with the spawn cell
     * and grown by [recordVisited] on every move step. Drives the
     * staircase-spawn gate (see [staircaseTemplateAllowed]).
     */
    private val _visitedCells: HashSet<Cell> = hashSetOf(initialPartyCell)

    /**
     * True once a boss-tagged template has been committed to this floor.
     * Drives [bossTemplateAllowed]; one-per-floor is part of the rule.
     */
    private var _bossPlaced: Boolean = false

    val floorCells: Set<Cell> get() = _floorCells
    val openConnectors: Set<Cell> get() = _openConnectors
    val placedConnectors: Set<Cell> get() = _placedConnectors
    val doors: Map<Cell, Door> get() = _doors
    val staircases: Set<Cell> get() = _staircases
    val visitedCount: Int get() = _visitedCells.size

    /** Current location of the party token. */
    var partyCell: Cell = initialPartyCell

    fun isFloor(c: Cell): Boolean = c in _floorCells
    fun isOpenConnector(c: Cell): Boolean = c in _openConnectors
    fun isStaircase(c: Cell): Boolean = c in _staircases

    fun isDoor(c: Cell): Boolean = c in _doors
    fun isLockedDoor(c: Cell): Boolean = _doors[c]?.locked == true
    fun doorAt(c: Cell): Door? = _doors[c]

    /** Permanently unlocks the door at [c]. No-op if [c] is not a door. */
    fun unlockDoor(c: Cell) {
        _doors[c]?.locked = false
    }

    /** Marks [cell] as explored. Called from the per-step move loop. */
    fun recordVisited(cell: Cell) {
        _visitedCells += cell
    }

    /**
     * Whether a staircase-bearing template (one with any blue pixels) is
     * currently eligible to be placed on this floor:
     *  - There must not already be a staircase on the floor (one per level).
     *  - The party must have explored at least [staircaseThresholdForDepth].
     */
    fun staircaseTemplateAllowed(): Boolean {
        if (_staircases.isNotEmpty()) return false
        return _visitedCells.size >= staircaseThresholdForDepth(depth)
    }

    /** True iff a boss template has already been placed on this floor. */
    val bossPlaced: Boolean get() = _bossPlaced

    /**
     * Whether a boss template (`boss*.png`) is currently eligible to spawn:
     * only on even-depth floors, and only once per floor.
     */
    fun bossTemplateAllowed(): Boolean {
        if (_bossPlaced) return false
        return depth % 2 == 0
    }

    /**
     * Tries to lay [template] down so its local (0,0) lands at [offset]
     * in dungeon-global coords.
     *
     * Placement is rejected if either rule below is violated:
     *
     *  1. **No hard overlap.** Any template-floor cell that lands on an
     *     existing floor cell must be a connector-on-connector overlap
     *     (red-on-red merge). Black-on-anything is a collision.
     *
     *  2. **One-cell visual gap.** Every non-merging cell of the new
     *     template must NOT be orthogonally adjacent to any existing floor
     *     cell other than a merging cell. This guarantees at least one
     *     background cell between any two rooms, so each room reads as a
     *     distinct outline; rooms only touch through the connector point
     *     where they were stitched.
     *
     * On success the new template's cells join [_floorCells], its
     * connectors join [_openConnectors] / [_placedConnectors] (merging
     * existing ones close the connector), its doors are registered in
     * [_doors] with a fresh lock roll (`Random(rng)` < [DOOR_LOCK_CHANCE]),
     * and its staircases join [_staircases].
     */
    fun tryPlaceTemplate(template: RoomTemplate, offset: Cell, rng: Random): Boolean {
        // Pass 1: validate overlaps and collect the cells where the new
        // template legitimately merges with an existing connector.
        val mergingCells = HashSet<Cell>()
        for (local in template.floorCells) {
            val world = Cell(local.x + offset.x, local.y + offset.y)
            if (world !in _floorCells) continue
            val guestIsConn = local in template.connectors
            val hostIsConn = world in _placedConnectors
            if (!(guestIsConn && hostIsConn)) return false
            mergingCells += world
        }

        // Pass 2: enforce the one-cell visual gap. Every non-merging cell
        // of the new template must have no orthogonal neighbor in the
        // existing floor (a merging cell is exempt because it is the
        // intentional doorway).
        for (local in template.floorCells) {
            val world = Cell(local.x + offset.x, local.y + offset.y)
            if (world in mergingCells) continue
            for (dir in CARDINAL_NEIGHBORS) {
                val neighbor = Cell(world.x + dir.x, world.y + dir.y)
                if (neighbor in _floorCells && neighbor !in mergingCells) {
                    return false
                }
            }
        }

        commitTemplate(template, offset, rng)
        return true
    }

    private fun commitTemplate(template: RoomTemplate, offset: Cell, rng: Random) {
        for (local in template.floorCells) {
            _floorCells += Cell(local.x + offset.x, local.y + offset.y)
        }
        for (local in template.connectors) {
            val world = Cell(local.x + offset.x, local.y + offset.y)
            if (world in _placedConnectors) {
                _openConnectors -= world
            } else {
                _placedConnectors += world
                _openConnectors += world
            }
        }
        for (local in template.doors) {
            val world = Cell(local.x + offset.x, local.y + offset.y)
            // Already-placed doors keep their existing lock state; a
            // template that re-uses a door cell (shouldn't happen, since
            // doors aren't connectors) would otherwise re-roll on every
            // overlap.
            if (world !in _doors) {
                _doors[world] = Door(locked = rng.nextFloat() < DOOR_LOCK_CHANCE)
            }
        }
        for (local in template.staircases) {
            _staircases += Cell(local.x + offset.x, local.y + offset.y)
        }
        if (template.isBoss) _bossPlaced = true
    }

    /**
     * Attempts to grow the floor by attaching a random template from
     * [library] at the open connector [from]. Returns true if a template
     * was placed.
     *
     * Strategy: the candidate pool is first filtered by three gates:
     *  - **Staircase gate** ([staircaseTemplateAllowed]): templates with
     *    blue pixels are kept out until the player has explored enough
     *    cells AND no staircase exists yet.
     *  - **Boss gate** ([bossTemplateAllowed]): boss templates are kept
     *    out unless the floor's depth is even AND no boss has spawned yet.
     *  - **Trap-prevention (cap) gate**: a template with fewer than 2
     *    connectors ("cap") closes the host connector and adds no new
     *    one. If [from] is the only open connector on the floor and the
     *    floor still owes the player a staircase, non-staircase caps
     *    are filtered out so the floor doesn't dead-end into a dungeon
     *    with nowhere to grow and no exit. Staircase caps are exempt
     *    because placing one fulfills the floor's purpose.
     *
     * Up to [maxAttempts] surviving candidates are then tried in random
     * order; each picks one of its own connectors at random and aligns
     * it on top of [from]; the placement is committed iff
     * [tryPlaceTemplate] accepts it.
     */
    fun tryExtendFromConnector(
        from: Cell,
        library: TemplateLibrary,
        rng: Random,
        maxAttempts: Int = DEFAULT_PLACE_ATTEMPTS,
    ): Boolean {
        if (from !in _openConnectors || library.isEmpty()) return false
        val allowStair = staircaseTemplateAllowed()
        val allowBoss = bossTemplateAllowed()
        // If from is the only open connector on the whole floor, every
        // non-staircase cap would leave the floor with zero open
        // connectors and (since allowStair gates staircase templates
        // out when the floor isn't ready for one) no exit. Refuse those.
        val capWouldTrap = _openConnectors.size <= 1 && _staircases.isEmpty()
        val pool = library.rooms.filter { t ->
            if (!allowStair && t.staircases.isNotEmpty()) return@filter false
            if (!allowBoss && t.isBoss) return@filter false
            if (capWouldTrap && t.connectors.size < 2 && t.staircases.isEmpty()) return@filter false
            true
        }
        if (pool.isEmpty()) return false
        repeat(maxAttempts) {
            val guest = pool.random(rng)
            if (guest.connectors.isEmpty()) return@repeat
            val guestConn = guest.connectors.random(rng)
            val offset = Cell(from.x - guestConn.x, from.y - guestConn.y)
            if (tryPlaceTemplate(guest, offset, rng)) return true
        }
        return false
    }

    companion object {
        const val DEFAULT_PLACE_ATTEMPTS = 50

        /**
         * Chance that a freshly-placed door is locked. 30% per the design
         * brief: high enough that the player meets the use-key / pick-lock /
         * force-open menu regularly, low enough that most doors don't gate
         * exploration.
         */
        const val DOOR_LOCK_CHANCE: Float = 0.30f

        /**
         * Number of cells the party must have stepped on before any
         * staircase-bearing template can spawn on floor 1.
         */
        const val STAIRCASE_BASE_THRESHOLD: Int = 15

        /**
         * Extra cells required per additional depth level. Floor 2 needs
         * 25, floor 3 needs 35, etc.
         */
        const val STAIRCASE_THRESHOLD_PER_DEPTH: Int = 10

        /** Minimum visited-cell count before a staircase may spawn on [depth]. */
        fun staircaseThresholdForDepth(depth: Int): Int =
            STAIRCASE_BASE_THRESHOLD + STAIRCASE_THRESHOLD_PER_DEPTH * (depth - 1)

        private val CARDINAL_NEIGHBORS = arrayOf(
            Cell(0, -1),
            Cell(0, 1),
            Cell(-1, 0),
            Cell(1, 0),
        )

        /**
         * Seeds a fresh Floor with a single starting template at the
         * origin. The entrance template's doors are rolled against [rng]
         * the same way runtime extensions are.
         *
         * The caller is responsible for filtering [entrance] to a
         * non-staircase template; this constructor doesn't second-guess
         * that choice, but in practice [FloorGenerator] always pre-filters.
         */
        fun withEntrance(depth: Int, seed: Long, entrance: RoomTemplate, rng: Random): Floor {
            val partyStart = entrance.floorCells.first()
            val doors = HashMap<Cell, Door>()
            for (cell in entrance.doors) {
                doors[cell] = Door(locked = rng.nextFloat() < DOOR_LOCK_CHANCE)
            }
            return Floor(
                depth = depth,
                seed = seed,
                initialFloorCells = entrance.floorCells,
                initialOpenConnectors = entrance.connectors,
                initialPlacedConnectors = entrance.connectors,
                initialDoors = doors,
                initialStaircases = entrance.staircases,
                initialPartyCell = partyStart,
            )
        }

        /** Single-cell floor used when no templates are available; prevents crashes. */
        fun fallback(depth: Int, seed: Long): Floor {
            val cell = Cell(0, 0)
            return Floor(
                depth = depth,
                seed = seed,
                initialFloorCells = setOf(cell),
                initialOpenConnectors = emptySet(),
                initialPlacedConnectors = emptySet(),
                initialDoors = emptyMap(),
                initialStaircases = emptySet(),
                initialPartyCell = cell,
            )
        }
    }
}
