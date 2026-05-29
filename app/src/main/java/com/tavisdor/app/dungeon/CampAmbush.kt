package com.tavisdor.app.dungeon

import kotlin.random.Random

/**
 * Camp skill ambush: decided when the cast starts, triggered mid-recovery
 * so the party may heal for a few ticks before combat begins.
 */
object CampAmbush {

    const val AMBUSH_CHANCE = 0.5f

    /**
     * Floors 1–5 → 1 enemy; +1 per 5 deeper floors, capped at 4.
     */
    fun enemyCountForDepth(floorDepth: Int): Int {
        val depth = floorDepth.coerceAtLeast(1)
        return minOf(4, 1 + (depth - 1) / 5)
    }

    /**
     * Returns the recovery tick index (0 .. [tickCount]-1) when an ambush
     * fires, or null when camping is safe (stairs room), the 50% roll fails,
     * or there are no recovery ticks.
     */
    fun rollAmbushTick(
        floor: Floor,
        partyCell: Cell,
        tickCount: Int,
        rng: Random,
    ): Int? {
        if (tickCount <= 0) return null
        if (floor.roomContainsStairs(partyCell)) return null
        if (rng.nextFloat() >= AMBUSH_CHANCE) return null
        return rng.nextInt(tickCount)
    }
}
