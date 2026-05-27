package com.tavisdor.app.locks

import kotlin.random.Random

/**
 * Lock resolution: Thief DEX + 1d3 pick, party STR + 1d6 force.
 */
object LockPick {

    data class DexCheckResult(
        val dex: Int,
        val die: Int,
        val total: Int,
        val lockLevel: Int,
        val success: Boolean,
    )

    data class StrCheckResult(
        val strength: Int,
        val die: Int,
        val total: Int,
        val lockLevel: Int,
        val success: Boolean,
    )

    fun rollDexPick(dex: Int, dungeonDepth: Int, rng: Random): DexCheckResult {
        val die = rng.nextInt(1, 4)
        val lockLevel = LockLevel.dexPickThreshold(dungeonDepth)
        val total = dex + die
        return DexCheckResult(
            dex = dex,
            die = die,
            total = total,
            lockLevel = lockLevel,
            success = total >= lockLevel,
        )
    }

    fun rollStrForce(strength: Int, dungeonDepth: Int, rng: Random): StrCheckResult {
        val die = rng.nextInt(1, 7)
        val lockLevel = LockLevel.strForceThreshold(dungeonDepth)
        val total = strength + die
        return StrCheckResult(
            strength = strength,
            die = die,
            total = total,
            lockLevel = lockLevel,
            success = total >= lockLevel,
        )
    }
}
