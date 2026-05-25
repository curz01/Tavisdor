package com.tavisdor.app.dungeon

import android.util.Log
import kotlin.random.Random

/**
 * Builds the initial layout of a [Floor] for a given (`depth`, `seed`) pair.
 * Runtime growth (the player tapping an open connector to reveal a new room)
 * uses the same machinery via [Floor.tryExtendFromConnector]; this object
 * only seeds the entrance and pre-grows the floor to [TARGET_ROOMS] rooms so
 * Floor N isn't a single chamber on cold start.
 *
 * Stitching rules (enforced by [Floor.tryPlaceTemplate]):
 *  1. Two templates may overlap only on cells that are connectors in BOTH
 *     (red-on-red). Anything else is a collision.
 *  2. A new template's non-merging cells must not be orthogonally adjacent
 *     to any existing floor cell other than the merging connector itself.
 *     This leaves at least one background cell between any two rooms so
 *     each room's outline stays visible.
 *
 * Staircase rule: exactly ONE staircase-bearing template ever lands on a
 * floor, and only after the party has explored
 * [Floor.staircaseThresholdForDepth] cells. Initial pre-grow happens with
 * `visited = 1` (just the spawn cell), so no staircase ever appears during
 * generation; the player must explore via tap-to-extend until the gate
 * opens, then keep extending until RNG picks a staircase template that
 * fits at one of the open connectors.
 */
object FloorGenerator {

    private const val TAG = "FloorGenerator"
    private const val TARGET_ROOMS = 12
    private const val MAX_FAIL_STREAK = 200

    fun generate(depth: Int, seed: Long, library: TemplateLibrary): Floor {
        if (library.isEmpty()) {
            Log.e(TAG, "TemplateLibrary is empty -> returning 1x1 fallback floor.")
            return Floor.fallback(depth, seed)
        }
        val rng = Random(seed)

        // The entrance must be a plain room: no staircase (we want the
        // exit gated by exploration) and no boss (boss spawns are gated
        // by depth + tap-extension). If every template is gated for some
        // reason, fall back to any so we don't crash, and log loudly.
        val entranceCandidates = library.rooms.filter { it.staircases.isEmpty() && !it.isBoss }
        val entrance = if (entranceCandidates.isNotEmpty()) {
            entranceCandidates.random(rng)
        } else {
            Log.w(TAG, "No plain (non-stair, non-boss) templates available; entrance will violate the spawn rules.")
            library.rooms.random(rng)
        }

        val floor = Floor.withEntrance(depth, seed, entrance, rng)
        Log.d(
            TAG,
            "Entrance: ${entrance.id} at (0,0); " +
                "${entrance.connectors.size} open connectors, " +
                "${entrance.doors.size} door(s), " +
                "${entrance.staircases.size} stair(s)" +
                (if (entrance.isBoss) ", BOSS" else "") + ".",
        )

        var placed = 1
        var fails = 0
        while (placed < TARGET_ROOMS && fails < MAX_FAIL_STREAK && floor.openConnectors.isNotEmpty()) {
            val host = floor.openConnectors.random(rng)
            // tryExtendFromConnector itself respects the staircase gate
            // (visited is still 1 here, so staircase templates are
            // filtered out for the entire pre-grow).
            if (floor.tryExtendFromConnector(host, library, rng)) {
                placed++
                fails = 0
            } else {
                fails++
            }
        }
        Log.d(
            TAG,
            "Stitched $placed template(s); ${floor.openConnectors.size} connector(s) remain open; " +
                "${floor.doors.size} door(s); ${floor.staircases.size} staircase(s). " +
                "Stair threshold for depth $depth: ${Floor.staircaseThresholdForDepth(depth)} visited cells.",
        )

        return floor
    }
}
