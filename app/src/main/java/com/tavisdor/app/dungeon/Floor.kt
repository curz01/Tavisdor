package com.tavisdor.app.dungeon

import android.util.Log
import com.tavisdor.app.enemies.Enemy
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
    initialStairsUp: Set<Cell> = emptySet(),
) {
    // Internal mutable state; exposed read-only to the rest of the app so
    // mutation only happens through the controlled methods below.
    private val _floorCells: HashSet<Cell> = HashSet(initialFloorCells)
    private val _openConnectors: HashSet<Cell> = HashSet(initialOpenConnectors)
    private val _placedConnectors: HashSet<Cell> = HashSet(initialPlacedConnectors)
    private val _doors: HashMap<Cell, Door> = HashMap(initialDoors)
    private val _staircases: HashSet<Cell> = HashSet(initialStaircases)
    private val _stairsUp: HashSet<Cell> = HashSet(initialStairsUp)

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

    /**
     * True once an `sp_*` (special event) template has been committed.
     * Drives [specialTemplateAllowed]; at most one special room per
     * floor regardless of depth.
     */
    private var _specialPlaced: Boolean = false

    /**
     * Per-open-connector "hallways since the last room" counter.
     * Drives the room <-> hall <-> room chain rule enforced by
     * [FloorGenerator]:
     *  - A connector exposed by a room-type template (start / room /
     *    end / sp / boss) is depth 0.
     *  - A connector exposed by a hall_* template is `hostDepth + 1`,
     *    where `hostDepth` is the depth of the open connector the
     *    hall was stitched onto.
     *
     * The generator reads [hallDepthAt] to decide which tile pool
     * (hall vs room) is valid at each pre-grow step. Closed
     * (merged) connectors stay in the map but are never queried.
     */
    private val _connectorHallDepth: HashMap<Cell, Int> = HashMap()

    // ---- Fog of war / room reveal ----
    //
    // A "placement" is one template instance laid down on the floor, in
    // dungeon-global coords. Each placement is hidden by default; the
    // moment the party steps onto any of its cells (`recordVisited`)
    // the whole placement is revealed and remains revealed for the rest
    // of the floor.
    //
    // [_cellToPlacements] is a many-to-many index: connector cells that
    // were merged by two templates appear in both sets so stepping on
    // a shared connector reveals both rooms simultaneously - natural
    // since the player is, geometrically, in both at that instant.
    //
    // Reveal state is intentionally NOT persisted in the save schema
    // yet; resume from save returns to floor-start and re-reveals
    // exactly the entrance template. Save schema migration TODO.

    private val _placements: MutableList<Set<Cell>> = mutableListOf()
    private val _placementRevealed: MutableList<Boolean> = mutableListOf()
    private val _cellToPlacements: HashMap<Cell, MutableList<Int>> = HashMap()
    private val _revealedCells: HashSet<Cell> = HashSet()

    // ---- Enemy state ----
    //
    // Enemies live ON the floor (not the Combat) so room-enter
    // detection has a place to look them up. Three parallel views:
    //   - _enemies:          flat list for iteration (initiative,
    //                        save/load when persistence lands)
    //   - _enemyByCell:      O(1) "is this cell occupied?" check
    //                        used by the move loop to halt before
    //                        stepping on an enemy
    //   - _enemyByPlacement: placement-index -> enemies-in-that-room,
    //                        powering the "spawn combat when the
    //                        party enters a populated room" trigger
    //
    // Combat does not own these collections; when an encounter
    // starts, it COPIES the relevant subset into Combat.enemies and
    // mutates that. Mirrored back here on combat end so the floor
    // reflects survivors / casualties.
    private val _enemies: MutableList<Enemy> = mutableListOf()
    private val _enemyByCell: HashMap<Cell, Enemy> = HashMap()
    private val _enemyByPlacement: HashMap<Int, MutableList<Enemy>> = HashMap()

    init {
        // The cells handed to us by the factory ARE the entrance
        // template's footprint. Register them as placement 0 and pre-
        // reveal it - the party spawns inside, so it would be jarring
        // to start on a hidden tile.
        registerPlacement(initialFloorCells)
        revealPlacement(0)
        // Entrance is a room-type template (a start_*), so every one
        // of its open connectors begins at hall-depth 0. The next
        // template stitched onto any of them must be a hall_*.
        for (c in initialOpenConnectors) {
            _connectorHallDepth[c] = 0
        }
    }

    val floorCells: Set<Cell> get() = _floorCells
    val openConnectors: Set<Cell> get() = _openConnectors
    val placedConnectors: Set<Cell> get() = _placedConnectors
    val doors: Map<Cell, Door> get() = _doors
    val staircases: Set<Cell> get() = _staircases
    val stairsUp: Set<Cell> get() = _stairsUp
    val visitedCount: Int get() = _visitedCells.size

    /** Cells the player has already seen; anything outside this set is hidden. */
    val revealedCells: Set<Cell> get() = _revealedCells

    /** True iff [c] has been revealed by exploration and may be rendered. */
    fun isRevealed(c: Cell): Boolean = c in _revealedCells

    // ---- Enemy accessors ----

    /** Flat enemy list across the whole floor. Read-only view. */
    val enemies: List<Enemy> get() = _enemies

    /**
     * Enemy occupying [cell], or null if none. Used by the move
     * loop to halt the party in front of a hostile, and by future
     * tap-to-attack input handling.
     */
    fun enemyAt(cell: Cell): Enemy? = _enemyByCell[cell]

    /**
     * Enemies in the same placement (room / hallway) as [cell].
     * Includes [cell]'s placement; if [cell] is a merge connector
     * sitting in two rooms, both rooms' enemies are returned.
     * Empty when [cell] has no placement (off-grid).
     */
    fun enemiesInRoomOf(cell: Cell): List<Enemy> {
        val placementIndices = _cellToPlacements[cell] ?: return emptyList()
        if (placementIndices.size == 1) {
            return _enemyByPlacement[placementIndices[0]]?.toList() ?: emptyList()
        }
        return placementIndices.flatMap { _enemyByPlacement[it]?.toList() ?: emptyList() }
    }

    /**
     * Any cell in placement 0 - the entrance template - usable as
     * the party-wipe respawn point. The entrance is registered in
     * [Floor]'s init block from the same `initialFloorCells` that
     * also seeded [partyCell], so a cell from that set is
     * guaranteed to be walkable and inside the spawn room.
     *
     * Returns null only if some future refactor stops pre-revealing
     * placement 0 - today the entrance is always there.
     */
    fun firstCellOfEntrance(): Cell? = _placements.firstOrNull()?.firstOrNull()

    /**
     * Move [enemy] from its current cell to [target]. Updates the
     * `_enemyByCell` index and (if the new cell sits in a
     * different placement) the `_enemyByPlacement` bucket so future
     * occupancy queries see the new position. Returns true on
     * success, false when the move was rejected:
     *   - target is not a floor cell
     *   - target is occupied by ANOTHER enemy (same enemy moving
     *     to its own cell is treated as success / no-op)
     *
     * The caller is responsible for any "is this a legal move"
     * checks beyond basic occupancy - e.g. distance limits, line of
     * sight, etc. live in the combat AI, not here.
     */
    fun moveEnemy(enemy: Enemy, target: Cell): Boolean {
        if (target == enemy.cell) return true
        if (target !in floorCells) return false
        val occupant = _enemyByCell[target]
        if (occupant != null && occupant !== enemy) return false
        val oldCell = enemy.cell
        // Detach from old cell + placement bucket.
        if (_enemyByCell[oldCell] === enemy) _enemyByCell.remove(oldCell)
        val oldPlacementIndices = _cellToPlacements[oldCell].orEmpty()
        for (idx in oldPlacementIndices) {
            _enemyByPlacement[idx]?.remove(enemy)
        }
        // Attach to new cell + placement bucket(s).
        enemy.cell = target
        _enemyByCell[target] = enemy
        val newPlacementIndices = _cellToPlacements[target].orEmpty()
        for (idx in newPlacementIndices) {
            _enemyByPlacement.getOrPut(idx) { mutableListOf() }.let { bucket ->
                if (enemy !in bucket) bucket += enemy
            }
        }
        return true
    }

    /**
     * Permanently removes [enemy] from the floor. Called at the end
     * of a combat encounter for every defeated enemy so the body
     * doesn't linger on the grid (or in [enemiesInRoomOf] queries)
     * after the fight. No-op if [enemy] was never registered.
     */
    fun removeEnemy(enemy: Enemy) {
        if (!_enemies.remove(enemy)) return
        if (_enemyByCell[enemy.cell] === enemy) {
            _enemyByCell.remove(enemy.cell)
        }
        // Walk all placement buckets - we don't keep a reverse index
        // from enemy to placement, but the per-placement list is at
        // most a handful of entries (room capacity caps at 4).
        for (bucket in _enemyByPlacement.values) {
            if (bucket.remove(enemy)) break
        }
    }

    /** Current location of the party token. */
    private var _partyCell: Cell = initialPartyCell
    var partyCell: Cell
        get() = _partyCell
        set(value) {
            if (_partyCell != value) {
                _partyCell = value
                invalidatePartyVisibility()
            }
        }

    /**
     * Cells the party can currently see: reachable walkable floor from
     * [partyCell] without crossing a locked door. Locked doors act as
     * walls for fog and enemy visibility.
     */
    private var _visibleToPartyCache: HashSet<Cell>? = null

    fun isVisibleToParty(c: Cell): Boolean {
        ensureVisibleToPartyCache()
        return c in _visibleToPartyCache!!
    }

    private fun invalidatePartyVisibility() {
        _visibleToPartyCache = null
    }

    private fun ensureVisibleToPartyCache() {
        if (_visibleToPartyCache != null) return
        val visible = HashSet<Cell>()
        if (_partyCell !in _floorCells) {
            _visibleToPartyCache = visible
            return
        }
        val queue = ArrayDeque<Cell>()
        visible += _partyCell
        queue.addLast(_partyCell)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            for (delta in CARDINAL_NEIGHBORS) {
                val n = Cell(cur.x + delta.x, cur.y + delta.y)
                if (n in visible) continue
                if (isLockedDoor(n)) continue
                if (n !in _floorCells) continue
                visible += n
                queue.addLast(n)
            }
        }
        // Closed doors sit on the frontier: show the tile so the player
        // can unlock it, but do not flood through into the room beyond.
        for (c in visible.toList()) {
            for (delta in CARDINAL_NEIGHBORS) {
                val n = Cell(c.x + delta.x, c.y + delta.y)
                if (isLockedDoor(n)) visible += n
            }
        }
        _visibleToPartyCache = visible
    }

    fun isFloor(c: Cell): Boolean = c in _floorCells
    fun isOpenConnector(c: Cell): Boolean = c in _openConnectors
    fun isStaircase(c: Cell): Boolean = c in _staircases
    fun isStairsUp(c: Cell): Boolean = c in _stairsUp

    fun isDoor(c: Cell): Boolean = c in _doors
    fun isLockedDoor(c: Cell): Boolean = _doors[c]?.locked == true
    fun doorAt(c: Cell): Door? = _doors[c]

    fun isDoorBruteDamaged(c: Cell): Boolean = _doors[c]?.bruteDamaged == true

    fun canAttemptStrForceOn(c: Cell): Boolean {
        val door = _doors[c] ?: return false
        return door.locked && !door.strForceAttempted
    }

    fun canAttemptDexPickOn(c: Cell): Boolean {
        val door = _doors[c] ?: return false
        return door.locked && !door.bruteDamaged
    }

    /** Permanently unlocks the door at [c]. No-op if [c] is not a door. */
    fun unlockDoor(c: Cell) {
        val door = _doors[c] ?: return
        if (!door.locked) return
        door.locked = false
        invalidatePartyVisibility()
    }

    /**
     * Assigns each locked door a matching [FloorKey] carrier on an enemy
     * reachable from [partyCell] without crossing another locked door.
     */
    fun assignLockKeyCarriers(rng: Random) {
        val lockedCells = _doors.filterValues { it.locked }.keys.toList()
        if (lockedCells.isEmpty()) return

        val eligible = _enemies.filter { enemy ->
            isReachableWithoutLockedDoors(partyCell, enemy.cell)
        }
        if (eligible.isEmpty()) {
            Log.w(TAG, "assignLockKeyCarriers: ${lockedCells.size} locked door(s) but no eligible enemies.")
            return
        }

        val shuffledLocks = lockedCells.shuffled(rng)
        val carriers = eligible.shuffled(rng).toMutableList()
        for (cell in shuffledLocks) {
            if (carriers.isEmpty()) carriers += eligible.shuffled(rng)
            val enemy = carriers.removeAt(0)
            val lockId = _doors[cell]?.lockId ?: continue
            if (lockId !in enemy.floorKeyLockIds) {
                enemy.floorKeyLockIds += lockId
            }
        }
        Log.d(
            TAG,
            "Assigned ${shuffledLocks.size} floor key(s) across ${eligible.size} eligible enemy(ies).",
        )
    }

    private fun isReachableWithoutLockedDoors(from: Cell, to: Cell): Boolean {
        if (from == to) return true
        if (to !in _floorCells) return false
        val seen = HashSet<Cell>()
        val queue = ArrayDeque<Cell>()
        seen += from
        queue.addLast(from)
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            if (cur == to) return true
            for (delta in CARDINAL_NEIGHBORS) {
                val n = Cell(cur.x + delta.x, cur.y + delta.y)
                if (n in seen) continue
                if (isLockedDoor(n)) continue
                if (n !in _floorCells) continue
                seen += n
                queue.addLast(n)
            }
        }
        return false
    }

    private fun newDoor(cell: Cell, locked: Boolean): Door =
        Door(
            locked = locked,
            axis = inferDoorAxis(cell, _floorCells),
            lockId = lockIdForCell(depth, cell),
        )

    private fun lockIdForCell(floorDepth: Int, cell: Cell): String =
        "d${floorDepth}_${cell.x}_${cell.y}"

    /**
     * Marks [cell] as explored. Called from the per-step move loop.
     *
     * Reveal rules:
     *   1. Reveal every placement containing [cell] (the room(s) the
     *      party just stepped into - normally one, but two when [cell]
     *      is a connector shared between two rooms).
     *   2. ONE-ROOM-AHEAD LOOKAHEAD: also reveal every placement that
     *      shares any cell (i.e. a merge connector) with a placement
     *      from step 1. This means as soon as you enter a room you
     *      can also see every room directly attached to it, so the
     *      exit arrows always sit on the BOUNDARY of what you can
     *      see and not on the cell you're standing on. Walking
     *      forward continuously slides that horizon outward.
     */
    fun recordVisited(cell: Cell) {
        _visitedCells += cell
        val seed = _cellToPlacements[cell] ?: return
        // Snapshot the seed indices: revealPlacement is idempotent but
        // we want a stable list to iterate while we collect neighbors.
        val direct = seed.toList()
        for (i in direct) revealPlacement(i)
        // Look one room ahead through any cell of the just-revealed
        // placements that participates in more than one placement
        // (those are the merge cells linking neighboring rooms).
        for (i in direct) {
            for (c in _placements[i]) {
                val others = _cellToPlacements[c] ?: continue
                if (others.size <= 1) continue
                for (j in others) {
                    if (j != i) revealPlacement(j)
                }
            }
        }
    }

    /**
     * Adds a new placement consisting of [cells] (in dungeon-global
     * coords) and updates the cell -> placement index. The placement
     * starts hidden; the caller must explicitly call [revealPlacement]
     * for any placement the player should see immediately (entrance
     * template only, today).
     */
    private fun registerPlacement(cells: Set<Cell>): Int {
        val idx = _placements.size
        _placements += cells
        _placementRevealed += false
        for (c in cells) {
            _cellToPlacements.getOrPut(c) { mutableListOf() } += idx
        }
        return idx
    }

    /**
     * Marks placement [idx] as revealed and folds its cells into
     * [_revealedCells]. Idempotent so a connector cell that lands in
     * an already-revealed placement doesn't re-do work.
     */
    private fun revealPlacement(idx: Int) {
        if (_placementRevealed[idx]) return
        _placementRevealed[idx] = true
        _revealedCells += _placements[idx]
    }

    /**
     * Re-applies the one-room-ahead lookahead rule to every currently
     * revealed placement. Called by [FloorGenerator.generate] AFTER
     * the pre-grow loop has added all initial rooms, because the
     * entrance's [revealPlacement] in `init` runs before any
     * neighbor exists so the lookahead inside [recordVisited]
     * couldn't have seen them. Idempotent.
     */
    fun applyAdjacentLookahead() {
        val newlyReveal = HashSet<Int>()
        for (i in _placements.indices) {
            if (!_placementRevealed[i]) continue
            for (c in _placements[i]) {
                val others = _cellToPlacements[c] ?: continue
                if (others.size <= 1) continue
                for (j in others) {
                    if (j != i && !_placementRevealed[j]) newlyReveal.add(j)
                }
            }
        }
        for (i in newlyReveal) revealPlacement(i)
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

    /** True iff a special template has already been placed on this floor. */
    val specialPlaced: Boolean get() = _specialPlaced

    /**
     * Whether a special template (`sp_*.png`) is currently eligible to
     * spawn: at most one per floor, no depth restriction.
     */
    fun specialTemplateAllowed(): Boolean = !_specialPlaced

    /**
     * Returns the hallway-chain depth associated with the open
     * connector at [c], or 0 when [c] isn't a tracked open connector
     * (e.g. it was the host of a successful merge and is no longer
     * open). 0 is the right "default" because non-open cells are
     * never legal hosts and the only callers gate on
     * `c in openConnectors` first anyway.
     */
    fun hallDepthAt(c: Cell): Int = _connectorHallDepth[c] ?: 0

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
        if (!canPlaceTemplate(template, offset)) return false
        commitTemplate(template, offset, rng)
        return true
    }

    /**
     * Read-only "would [tryPlaceTemplate] accept [template] at [offset]?".
     * Runs the same overlap + visual-gap checks but never mutates Floor
     * state. Lets callers (renderer, generator winnability check)
     * probe placements without rolling state forward.
     */
    fun canPlaceTemplate(template: RoomTemplate, offset: Cell): Boolean {
        // Pass 1: every cell that lands on existing floor must be a
        // red-on-red merge.
        val mergingCells = HashSet<Cell>()
        for (local in template.floorCells) {
            val world = Cell(local.x + offset.x, local.y + offset.y)
            if (world !in _floorCells) continue
            val guestIsConn = local in template.connectors
            val hostIsConn = world in _placedConnectors
            if (!(guestIsConn && hostIsConn)) return false
            mergingCells += world
        }
        // Pass 2: one-cell visual gap. Non-merging cells of the guest
        // must not be orthogonally adjacent to any existing floor cell
        // other than a merging cell.
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
        return true
    }

    /**
     * True iff at least one template in [candidates] can be stitched
     * onto the open connector at [host], for ANY of that template's
     * own connectors. Used by:
     *   - The renderer to suppress "exit this way" arrows on open
     *     connectors that can never extend (avoids leading the player
     *     to a dead end).
     *   - [FloorGenerator] to detect floors that would trap the
     *     player before the staircase gate opens.
     *
     * Returns false immediately if [host] is not an open connector
     * (closed connectors can't grow anything regardless).
     */
    fun connectorCanFit(host: Cell, candidates: List<RoomTemplate>): Boolean {
        if (host !in _openConnectors) return false
        for (template in candidates) {
            for (guestConn in template.connectors) {
                val offset = Cell(host.x - guestConn.x, host.y - guestConn.y)
                if (canPlaceTemplate(template, offset)) return true
            }
        }
        return false
    }

    private fun commitTemplate(template: RoomTemplate, offset: Cell, rng: Random) {
        // Build the placement's dungeon-global footprint up-front so
        // we can register it for fog-of-war reveal. Doing this in a
        // single pass avoids walking template.floorCells twice.
        val placementCells = HashSet<Cell>(template.floorCells.size)
        for (local in template.floorCells) {
            val world = Cell(local.x + offset.x, local.y + offset.y)
            _floorCells += world
            placementCells += world
        }
        // Pass over connectors twice: first identify the merge cells
        // (so we can read the host's hall-depth BEFORE the merge
        // closes it), then update the open / placed sets and assign
        // depths to any newly exposed connectors.
        val templateWorldConns = ArrayList<Cell>(template.connectors.size)
        var hostDepth = 0
        var sawHost = false
        for (local in template.connectors) {
            val world = Cell(local.x + offset.x, local.y + offset.y)
            templateWorldConns += world
            if (world in _placedConnectors) {
                // This is a merge with an existing connector. If the
                // existing connector is still open it's a candidate
                // host; capture its depth so the chain rule extends
                // through it. If a template happens to merge on two
                // open connectors at once (rare), the highest depth
                // wins so the new connectors inherit the more-
                // restrictive bound.
                if (world in _openConnectors) {
                    val d = _connectorHallDepth[world] ?: 0
                    if (!sawHost || d > hostDepth) hostDepth = d
                    sawHost = true
                }
            }
        }
        // New connectors inherit hostDepth+1 when the placed template
        // is a hall, otherwise they reset to 0 (room boundary). Caps
        // and rooms both reset; the only path that bumps the chain
        // is hall_*.
        val newDepth = if (template.isHall) hostDepth + 1 else 0
        for (world in templateWorldConns) {
            if (world in _placedConnectors) {
                _openConnectors -= world
            } else {
                _placedConnectors += world
                _openConnectors += world
                _connectorHallDepth[world] = newDepth
            }
        }
        for (local in template.doors) {
            val world = Cell(local.x + offset.x, local.y + offset.y)
            // Already-placed doors keep their existing lock state; a
            // template that re-uses a door cell (shouldn't happen, since
            // doors aren't connectors) would otherwise re-roll on every
            // overlap.
            if (world !in _doors) {
                _doors[world] = newDoor(
                    world,
                    locked = rng.nextFloat() < DOOR_LOCK_CHANCE,
                )
            }
        }
        for (local in template.staircases) {
            _staircases += Cell(local.x + offset.x, local.y + offset.y)
        }
        for (local in template.stairsUp) {
            _stairsUp += Cell(local.x + offset.x, local.y + offset.y)
        }
        if (template.isBoss) _bossPlaced = true
        if (template.isSpecial) _specialPlaced = true

        // Register the placement last so the staircase / boss / door
        // updates above already settled; reveal stays opt-in until the
        // party walks into one of these cells.
        val placementIdx = registerPlacement(placementCells)

        // Roll for enemies AFTER the placement is registered so the
        // spawner can correlate enemies to this placement's index.
        // Start tiles, boss tiles, and end-caps are filtered inside
        // EnemySpawner; everything else goes through the standard
        // 10/30/30/20/10 table clamped by room capacity.
        val spawned = EnemySpawner.spawnFor(
            template = template,
            offset = offset,
            floorDepth = depth,
            rng = rng,
        )
        if (spawned.isNotEmpty()) {
            registerEnemies(placementIdx, spawned)
        }
    }

    /**
     * Adds [newEnemies] to the floor under [placementIdx]. Maintains
     * all three parallel views (`_enemies`, `_enemyByCell`,
     * `_enemyByPlacement`). Silently drops an enemy whose target
     * cell is already occupied - shouldn't happen with the spawner's
     * shuffled-then-take strategy, but guards against future
     * mistakes.
     */
    private fun registerEnemies(placementIdx: Int, newEnemies: List<Enemy>) {
        if (newEnemies.isEmpty()) return
        val bucket = _enemyByPlacement.getOrPut(placementIdx) { mutableListOf() }
        for (e in newEnemies) {
            if (_enemyByCell.containsKey(e.cell)) continue
            _enemies += e
            _enemyByCell[e.cell] = e
            bucket += e
        }
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
    /**
     * Generator-side placement helper. Picks a random orientation for
     * a random template in [candidates] and tries to merge one of its
     * connectors onto the open connector at [host]. Returns the
     * template that successfully landed, or `null` if every attempt
     * was rejected by the merge / visual-gap rules.
     *
     * Distinct from [tryExtendFromConnector] in two ways:
     *  - The caller fully controls the candidate pool. This is what
     *    lets [FloorGenerator] enforce the room <-> hall <-> room
     *    chain rule (it passes a hall-only or room-only pool based on
     *    [hallDepthAt]).
     *  - It returns the placed template so the caller can decide
     *    whether to bump the room budget (rooms / sp / boss) or
     *    treat the placement as "free" (halls).
     */
    fun tryPlaceAtConnector(
        host: Cell,
        candidates: List<RoomTemplate>,
        rng: Random,
        maxAttempts: Int = DEFAULT_PLACE_ATTEMPTS,
    ): RoomTemplate? {
        if (host !in _openConnectors || candidates.isEmpty()) return null
        repeat(maxAttempts) {
            val guest = candidates.random(rng)
            if (guest.connectors.isEmpty()) return@repeat
            val guestConn = guest.connectors.random(rng)
            val offset = Cell(host.x - guestConn.x, host.y - guestConn.y)
            if (tryPlaceTemplate(guest, offset, rng)) return guest
        }
        return null
    }

    fun tryExtendFromConnector(
        from: Cell,
        library: TemplateLibrary,
        rng: Random,
        maxAttempts: Int = DEFAULT_PLACE_ATTEMPTS,
    ): Boolean {
        if (from !in _openConnectors || library.isEmpty()) return false
        val allowStair = staircaseTemplateAllowed()
        val allowBoss = bossTemplateAllowed()
        val allowSpecial = specialTemplateAllowed()
        // If from is the only open connector on the whole floor, every
        // non-staircase cap would leave the floor with zero open
        // connectors and (since allowStair gates staircase templates
        // out when the floor isn't ready for one) no exit. Refuse those.
        val capWouldTrap = _openConnectors.size <= 1 && _staircases.isEmpty()

        // Pool selection:
        //  - allowStair == true  -> ONLY end_* templates (the dungeon
        //    exit always lives in an authored end tile). If none fit
        //    at this connector we return false; the player can tap
        //    another connector and try again.
        //  - allowStair == false -> filler tiles: never start_*
        //    (entrance-only), never end_* (exit-only, gated above),
        //    never anything that happens to carry a stair pixel (only
        //    end tiles are allowed to spawn stairs). `sp_*` tiles
        //    join the filler pool, but only until one has been placed
        //    (allowSpecial). Boss tiles are layered on top and only
        //    allowed on even-depth floors via allowBoss.
        val pool = if (allowStair) {
            library.rooms.filter { t ->
                if (!t.isEnd) return@filter false
                if (!allowBoss && t.isBoss) return@filter false
                if (capWouldTrap && t.connectors.size < 2 && t.staircases.isEmpty()) return@filter false
                true
            }
        } else {
            library.rooms.filter { t ->
                if (t.isStart) return@filter false
                if (t.isEnd) return@filter false
                if (t.staircases.isNotEmpty()) return@filter false
                if (!allowBoss && t.isBoss) return@filter false
                if (!allowSpecial && t.isSpecial) return@filter false
                if (capWouldTrap && t.connectors.size < 2 && t.staircases.isEmpty()) return@filter false
                true
            }
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

        private const val TAG = "Floor"

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
                val locked = rng.nextFloat() < DOOR_LOCK_CHANCE
                doors[cell] = Door(
                    locked = locked,
                    axis = inferDoorAxis(cell, entrance.floorCells),
                    lockId = "d${depth}_${cell.x}_${cell.y}",
                )
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
                initialStairsUp = entrance.stairsUp,
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
