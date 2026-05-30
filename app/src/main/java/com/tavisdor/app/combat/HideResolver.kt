package com.tavisdor.app.combat

import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.enemies.Enemy
import com.tavisdor.app.party.Hero
import kotlin.math.abs
import kotlin.random.Random

/**
 * Thief Hide: party is unseen by enemies at Manhattan distance
 * [HIDE_SAFE_DISTANCE] or farther. Closer enemies may spot the party
 * with an INT + DEX check (1d10 per roll; 1 fumbles, 10 auto-succeeds).
 */
object HideResolver {

    /** Enemies this many cells away (or farther) do not perceive the party. */
    const val HIDE_SAFE_DISTANCE: Int = 2

    data class PartyHideState(
        val casterSlot: Int,
        val casterInt: Int,
        val casterDex: Int,
        val casterLevel: Int,
    ) {
        companion object {
            fun fromHero(slot: Int, hero: Hero): PartyHideState =
                PartyHideState(
                    casterSlot = slot,
                    casterInt = hero.intelligence,
                    casterDex = hero.dexterity,
                    casterLevel = hero.level,
                )
        }
    }

    /**
     * True when [enemy] is close enough to attempt a Hide perception
     * check (Manhattan distance strictly less than [HIDE_SAFE_DISTANCE]).
     */
    fun isWithinPerceptionRange(partyCell: Cell, enemyCell: Cell): Boolean =
        manhattan(partyCell, enemyCell) < HIDE_SAFE_DISTANCE

    /**
     * Living enemies within Hide perception range of [partyCell] that
     * successfully spot the party on an opposed check.
     */
    fun enemiesSpottingParty(
        enemies: Iterable<Enemy>,
        partyCell: Cell,
        hide: PartyHideState,
        rng: Random,
    ): List<Enemy> =
        enemies.filter { enemy ->
            isWithinPerceptionRange(partyCell, enemy.cell) &&
                enemyDetectsParty(enemy, partyCell, hide, rng)
        }

    /**
     * True when [enemy] spots the party while hide is active.
     * Far enemies never detect; close enemies roll vs the hider's stats.
     */
    fun enemyDetectsParty(
        enemy: Enemy,
        partyCell: Cell,
        hide: PartyHideState,
        rng: Random,
    ): Boolean {
        if (!isWithinPerceptionRange(partyCell, enemy.cell)) return false
        val roll = CombatMath.rollCheckDie(rng)
        val levelDelta = (enemy.level - hide.casterLevel).coerceAtLeast(0)
        var enemyLevelBonus = 0
        repeat(levelDelta) {
            enemyLevelBonus += CombatMath.rollCheckDie(rng)
        }
        val heroStat = hide.casterInt + hide.casterDex
        val enemyStat = enemy.intelligence + enemy.dexterity + enemyLevelBonus
        // Hide holds when the hero wins the opposed check (not detected).
        return !CombatMath.checkSucceeds(heroStat, enemyStat, roll)
    }

    private fun manhattan(a: Cell, b: Cell): Int =
        abs(a.x - b.x) + abs(a.y - b.y)
}
