package com.tavisdor.app.dungeon

import android.util.Log
import kotlin.random.Random

/**
 * Places treasure chests on random room placements after the floor
 * layout is fully generated. Hallways and the starting room are excluded.
 */
object ChestSpawner {

    private const val TAG = "ChestSpawner"
    private const val CHEST_LOCK_CHANCE = 0.50f

    fun spawn(floor: Floor, rng: Random) {
        val count = rollChestCount(floor.depth, rng)
        if (count <= 0) return

        val eligible = floor.placementIndicesWithSpawnCells()
        if (eligible.isEmpty()) {
            Log.w(TAG, "No eligible placements for chests on depth ${floor.depth}.")
            return
        }

        val placements = eligible.shuffled(rng).take(count)
        var placed = 0
        for (placementIdx in placements) {
            val cell = floor.pickRandomSpawnCell(placementIdx, rng) ?: continue
            val locked = rng.nextFloat() < CHEST_LOCK_CHANCE
            floor.placeChest(cell = cell, locked = locked)
            placed++
        }
        Log.d(TAG, "Placed $placed treasure chest(s) on depth ${floor.depth} (target=$count).")
    }

    /**
     * Chest count by dungeon level band. Floor 1 is capped at one chest.
     */
    fun rollChestCount(depth: Int, rng: Random): Int = when {
        depth == 1 -> 1
        depth <= 9 -> rng.nextInt(1, 3)
        depth <= 19 -> rng.nextInt(2, 4)
        depth <= 29 -> rng.nextInt(2, 5)
        else -> rng.nextInt(3, 7)
    }

    private fun Floor.placementIndicesWithSpawnCells(): List<Int> =
        (0 until placementCount).filter { idx ->
            allowsTreasureChestInPlacement(idx) && spawnCellsInPlacement(idx).isNotEmpty()
        }

    private fun Floor.pickRandomSpawnCell(placementIndex: Int, rng: Random): Cell? {
        val candidates = spawnCellsInPlacement(placementIndex)
        if (candidates.isEmpty()) return null
        return candidates.random(rng)
    }

    private fun Floor.spawnCellsInPlacement(placementIndex: Int): List<Cell> {
        val cells = cellsInPlacement(placementIndex)
        return cells.filter { c ->
            c in floorCells &&
                !isDoor(c) &&
                !isStaircase(c) &&
                !isStairsUp(c) &&
                chestAt(c) == null &&
                enemyAt(c) == null &&
                c != partyCell
        }
    }
}
