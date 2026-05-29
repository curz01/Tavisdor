package com.tavisdor.app.party

/**
 * Distributes HP / MP restoration across [tickCount] animation steps so
 * the bar fills smoothly while the utility cast plays.
 */
class UtilityRecoverySession(
    private val hpRemaining: IntArray,
    private val mpRemaining: IntArray,
    val tickCount: Int,
) {
    private var ticksApplied: Int = 0

    fun applyTick(heroes: List<Hero>) {
        if (ticksApplied >= tickCount) return
        val ticksLeft = tickCount - ticksApplied
        for (slot in heroes.indices) {
            val hero = heroes[slot]
            if (!hero.isAlive) continue
            val hpChunk = chunk(hpRemaining[slot], ticksLeft)
            val mpChunk = chunk(mpRemaining[slot], ticksLeft)
            if (hpChunk > 0) hero.heal(hpChunk)
            if (mpChunk > 0) hero.restoreMp(mpChunk)
            hpRemaining[slot] -= hpChunk
            mpRemaining[slot] -= mpChunk
        }
        ticksApplied++
    }

    fun flushRemaining(heroes: List<Hero>) {
        for (slot in heroes.indices) {
            val hero = heroes[slot]
            if (!hero.isAlive) continue
            if (hpRemaining[slot] > 0) hero.heal(hpRemaining[slot])
            if (mpRemaining[slot] > 0) hero.restoreMp(mpRemaining[slot])
            hpRemaining[slot] = 0
            mpRemaining[slot] = 0
        }
        ticksApplied = tickCount
    }

    private fun chunk(remaining: Int, ticksLeft: Int): Int {
        if (remaining <= 0 || ticksLeft <= 0) return 0
        return (remaining + ticksLeft - 1) / ticksLeft
    }

    companion object {
        fun fromTargets(targets: List<HeroRecoveryTarget>, tickCount: Int): UtilityRecoverySession {
            val hp = IntArray(4)
            val mp = IntArray(4)
            for (t in targets) {
                if (t.slot in 0..3) {
                    hp[t.slot] = t.totalHp.coerceAtLeast(0)
                    mp[t.slot] = t.totalMp.coerceAtLeast(0)
                }
            }
            return UtilityRecoverySession(hp, mp, tickCount.coerceAtLeast(1))
        }
    }
}

data class HeroRecoveryTarget(
    val slot: Int,
    val totalHp: Int,
    val totalMp: Int,
)
