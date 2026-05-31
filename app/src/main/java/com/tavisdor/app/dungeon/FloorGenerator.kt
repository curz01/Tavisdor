package com.tavisdor.app.dungeon

import android.util.Log
import kotlin.random.Random

/**
 * Builds the FULL layout of a [Floor] for a given (`depth`, `seed`) pair.
 *
 * Authoring vocabulary (filename prefix -> template role):
 *   - `start_*`     -> entrance room (1 per floor, lands at origin)
 *   - `room_*`      -> destination room (counts toward budget)
 *   - `hall_*`      -> hallway segment (does NOT count toward budget)
 *   - `end_*`       -> stairs-down room (1 per floor, placed last
 *                      at the deepest open connector)
 *   - `end_room_*`  -> hallway end-cap (seals open connectors after
 *                      the budget is met; does NOT count)
 *   - `sp_*`        -> special event room (<= 1 per floor)
 *   - `boss*`       -> boss room (only even depths, <= 1 per floor)
 *
 * Generation pipeline:
 *  1. Pick an entrance from the `start_*` pool and lay it down.
 *  2. Pre-grow: at each step, pick a random open connector and
 *     stitch on a hall or a room. The choice is forced by the
 *     connector's [Floor.hallDepthAt]:
 *        depth == 0           -> must be `hall_*` (room edges are
 *                                always followed by a hallway).
 *        depth in 1..MAX-1    -> hall or room, RNG picks.
 *        depth >= MAX         -> must be a room (chain cap).
 *     Loop until the room budget is filled (start + room/sp/boss
 *     placements) MINUS 1 (we reserve the last slot for the
 *     stairs-down room).
 *  3. Reserve the open connector farthest from the entrance for the
 *     stairs-down `end_*` room (placed at runtime once enough rooms
 *     have been revealed; see [Floor.staircaseTemplateAllowed]).
 *  4. Cap every remaining open connector with an `end_room_tile`
 *     so the dungeon ships fully sealed - no runtime tap-to-extend.
 *
 * Determinism: a given seed produces the same layout every call
 * because every RNG draw runs against the same [Random] instance in
 * the same order, and the open-connector set is iterated through a
 * canonicalized list when randomization is needed.
 */
object FloorGenerator {

    private const val TAG = "FloorGenerator"

    /**
     * Total budget for room-type placements (start + room + sp +
     * boss + end). Halls and end-caps don't count.
     *
     * Floor 1 starts at this many rooms; depths beyond 1 add
     * [ROOMS_PER_DEPTH] more so the deeper you go the bigger the
     * dungeon. Tuned to be playable with the current asset pool
     * (currently 2 `room_*` templates); rebalance after more room
     * sprites are authored.
     */
    private const val ROOMS_BASE = 6
    private const val ROOMS_PER_DEPTH = 1

    /**
     * Hard cap on the per-connector hall chain. Matches the
     * "1 to 3 halls between rooms" rule from the design brief.
     * Increase if longer corridors feel right; the chain rule
     * still requires at least one hall before any new room.
     */
    private const val MAX_HALL_CHAIN = 3

    /**
     * Safety bound on the pre-grow loop. The loop already exits the
     * instant the room budget is full or no open connector can be
     * extended, so this only kicks in for pathological template sets
     * (everything collides every time).
     */
    private const val MAX_FAIL_STREAK = 200

    fun generate(depth: Int, seed: Long, library: TemplateLibrary): Floor {
        if (library.isEmpty()) {
            Log.e(TAG, "TemplateLibrary is empty -> returning 1x1 fallback floor.")
            return Floor.fallback(depth, seed)
        }
        val rng = Random(seed)

        // ---- Template pools ----
        val hallTiles = library.rooms.filter { it.isHall }
        val roomFillerTiles = library.rooms.filter { it.isRoom }
        val endTiles = library.rooms.filter { it.isEnd }
        val endCapTiles = library.rooms.filter { it.isEndCap }
        val spTiles = library.rooms.filter { it.isSpecial }
        val bossTiles = library.rooms.filter { it.isBoss }

        warnIfPoolEmpty(hallTiles, "hall_*")
        warnIfPoolEmpty(roomFillerTiles, "room_*")
        warnIfPoolEmpty(endTiles, "end_* (stairs-down)")
        warnIfPoolEmpty(endCapTiles, "end_room_*")

        // ---- 1. Entrance ----
        val entrance = pickEntrance(library, rng)
        val floor = Floor.withEntrance(depth, seed, entrance, rng)
        val stairsRequired = Floor.staircaseRoomThresholdForFloor(depth, seed)
        floor.setStaircaseRoomsRequired(stairsRequired)
        Log.d(
            TAG,
            "Entrance: ${entrance.id} at (0,0); " +
                "${entrance.connectors.size} open connectors, " +
                "${entrance.doors.size} door(s); " +
                "stairs after $stairsRequired revealed room(s) " +
                "(range ${Floor.staircaseRoomThresholdRangeForFloor(depth)}).",
        )

        // ---- 2. Pre-grow ----
        val targetRooms = roomTarget(depth)
        // Reserve one room slot for the stairs-down room placed in
        // step 3. If no end_* template exists at all, don't reserve
        // (we'd otherwise under-fill the floor for no reason).
        val preGrowTarget = if (endTiles.isEmpty()) targetRooms else targetRooms - 1
        var roomCount = 1 // entrance is a room
        var hallCount = 0
        var fails = 0
        while (
            roomCount < preGrowTarget &&
            fails < MAX_FAIL_STREAK &&
            floor.openConnectors.isNotEmpty()
        ) {
            // Iterate connectors in randomized order so the next host
            // isn't always the most recently added cell. Snapshot to
            // a list so the underlying set can mutate during the
            // placement attempt below.
            val host = floor.openConnectors.random(rng)
            val depthAtHost = floor.hallDepthAt(host)
            val pool = poolForDepth(
                depthAtHost = depthAtHost,
                hallTiles = hallTiles,
                roomTiles = currentRoomFillerPool(
                    floor = floor,
                    roomFillerTiles = roomFillerTiles,
                    spTiles = spTiles,
                    bossTiles = bossTiles,
                ),
            )
            val placed = if (pool.isEmpty()) {
                null
            } else {
                floor.tryPlaceAtConnector(host, pool, rng)
            }
            if (placed == null) {
                fails++
                continue
            }
            fails = 0
            if (placed.isHall) hallCount++ else roomCount++
        }
        Log.d(
            TAG,
            "Pre-grow done: rooms=$roomCount halls=$hallCount " +
                "openConnectors=${floor.openConnectors.size} " +
                "spPlaced=${floor.specialPlaced} bossPlaced=${floor.bossPlaced} " +
                "fails=$fails (target=$preGrowTarget).",
        )

        // ---- 3. Reserve deepest connector for deferred stairs-down ----
        if (endTiles.isNotEmpty()) {
            val stairHost = farthestOpenConnector(floor)
            if (stairHost != null) {
                floor.reserveConnectorForPendingStairs(stairHost)
                Log.d(
                    TAG,
                    "Reserved $stairHost for stairs-down (revealed-room gate).",
                )
            } else {
                Log.w(
                    TAG,
                    "No open connector to reserve for stairs-down.",
                )
            }
        }

        // ---- 4. Cap remaining open connectors ----
        capRemainingConnectors(floor, endCapTiles, rng)
        Log.d(
            TAG,
            "Capping done. openConnectors=${floor.openConnectors.size} " +
                "(should be 0 when end_room_* covers every orientation).",
        )

        ChestSpawner.spawn(floor, rng)
        floor.assignLockKeyCarriers(rng)
        floor.finalizeEntranceFog()

        return floor
    }

    /**
     * Picks the entrance template. `start_*` is the authored choice;
     * if none exist we fall back to a plain room_* / legacy filler
     * so the game still boots while assets are being assembled.
     */
    private fun pickEntrance(library: TemplateLibrary, rng: Random): RoomTemplate {
        val startCandidates = library.rooms.filter { it.isStart }
        if (startCandidates.isNotEmpty()) return startCandidates.random(rng)
        Log.w(TAG, "No start_* templates available; falling back to a plain filler.")
        val fillerCandidates = library.rooms.filter {
            !it.isStart && !it.isEnd && !it.isBoss && !it.isSpecial &&
                !it.isEndCap && !it.isHall && it.staircases.isEmpty()
        }
        if (fillerCandidates.isNotEmpty()) return fillerCandidates.random(rng)
        Log.w(TAG, "No fillers either; entrance will violate spawn rules.")
        return library.rooms.random(rng)
    }

    /** Returns the room-type pool currently allowed for placement, honoring sp / boss gates. */
    private fun currentRoomFillerPool(
        floor: Floor,
        roomFillerTiles: List<RoomTemplate>,
        spTiles: List<RoomTemplate>,
        bossTiles: List<RoomTemplate>,
    ): List<RoomTemplate> {
        val allowSp = floor.specialTemplateAllowed()
        val allowBoss = floor.bossTemplateAllowed()
        if (allowSp && allowBoss) return roomFillerTiles + spTiles + bossTiles
        if (allowSp) return roomFillerTiles + spTiles
        if (allowBoss) return roomFillerTiles + bossTiles
        return roomFillerTiles
    }

    /**
     * Returns the combined candidate pool valid at a connector
     * whose [Floor.hallDepthAt] is [depthAtHost]:
     *   - 0                -> must place a hall (room edge follows
     *                          with a hallway).
     *   - 1..MAX-1         -> hall or room, RNG-picked.
     *   - >= MAX           -> must place a room (chain cap).
     */
    private fun poolForDepth(
        depthAtHost: Int,
        hallTiles: List<RoomTemplate>,
        roomTiles: List<RoomTemplate>,
    ): List<RoomTemplate> = when {
        depthAtHost <= 0 -> hallTiles
        depthAtHost >= MAX_HALL_CHAIN -> roomTiles
        else -> hallTiles + roomTiles
    }

    private fun farthestOpenConnector(floor: Floor): Cell? =
        floor.openConnectors
            .map { it to bfsDistance(floor, floor.partyCell, it) }
            .maxByOrNull { it.second }
            ?.first

    /**
     * Caps every remaining open connector with an `end_room_*`
     * template. Iterates over a snapshot so the underlying set can
     * mutate during placement. Connectors that no end_room_*
     * orientation can fit are logged and left open; the player will
     * never see them as exits (the renderer's exit marker is keyed
     * on hidden-neighbor floor cells, not open connectors), but the
     * generator surfaces the warning so authoring gaps are visible.
     */
    private fun capRemainingConnectors(
        floor: Floor,
        endCapTiles: List<RoomTemplate>,
        rng: Random,
    ) {
        if (endCapTiles.isEmpty()) {
            if (floor.openConnectors.isNotEmpty()) {
                Log.w(
                    TAG,
                    "No end_room_* caps available and " +
                        "${floor.openConnectors.size} connector(s) still open; " +
                        "dungeon will ship with visible red pixels.",
                )
            }
            return
        }
        val pending = floor.openConnectors.toList()
        var capped = 0
        var failed = 0
        for (host in pending) {
            if (host !in floor.openConnectors) continue // already absorbed via a merge
            if (floor.isReservedStairConnector(host)) continue
            if (floor.tryPlaceAtConnector(host, endCapTiles, rng) != null) {
                capped++
            } else {
                failed++
                Log.w(TAG, "No end_room_* cap fits at $host; left open.")
            }
        }
        Log.d(TAG, "End-caps placed: $capped success / $failed fail.")
    }

    /**
     * BFS over [Floor.floorCells] using 4-direction adjacency.
     * Returns the step count from [from] to [to], or
     * [Int.MAX_VALUE] when [to] is unreachable. Used by stairs-down
     * placement to pick the "deepest" open connector.
     */
    private fun bfsDistance(floor: Floor, from: Cell, to: Cell): Int {
        if (from == to) return 0
        val visited = HashSet<Cell>()
        val queue = ArrayDeque<Pair<Cell, Int>>()
        queue.addLast(from to 0)
        visited += from
        while (queue.isNotEmpty()) {
            val (cell, dist) = queue.removeFirst()
            for (d in CARDINAL) {
                val n = Cell(cell.x + d.x, cell.y + d.y)
                if (n in visited) continue
                if (n !in floor.floorCells) continue
                if (n == to) return dist + 1
                visited += n
                queue.addLast(n to (dist + 1))
            }
        }
        return Int.MAX_VALUE
    }

    private fun roomTarget(depth: Int): Int =
        ROOMS_BASE + (depth - 1).coerceAtLeast(0) * ROOMS_PER_DEPTH

    private fun warnIfPoolEmpty(pool: List<RoomTemplate>, label: String) {
        if (pool.isEmpty()) Log.w(TAG, "No $label templates in library.")
    }

    private val CARDINAL = arrayOf(
        Cell(0, -1),
        Cell(0, 1),
        Cell(-1, 0),
        Cell(1, 0),
    )
}
