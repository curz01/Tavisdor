package com.tavisdor.app.combat

import kotlin.random.Random

/** One slot in a combat's turn order. */
data class InitiativeEntry(
    val kind: Kind,
    /** Index into the party's hero list or the encounter's enemy list, depending on [kind]. */
    val index: Int,
    val dexterity: Int,
    /**
     * Tie-break d6 used to disambiguate when [dexterity] is shared
     * with another entry. Captured at initiative-build time so the
     * order is STABLE across rounds - rerolling each round would
     * confuse the player ("why did Mage jump ahead of Thief?").
     *
     * `null` when this entry didn't participate in any tie-break,
     * which keeps it out of UI tooltips for the common case. Logged
     * value is the WINNING roll; losers' rolls are discarded.
     */
    val tiebreakRoll: Int? = null,
) {
    enum class Kind { HERO, ENEMY }
}

/**
 * Builds a per-encounter turn order. Sort key:
 *
 *   1. DEX descending (high DEX = goes first).
 *   2. On a tie, both combatants roll a d6 each; higher roll wins.
 *      Re-roll on a draw until one wins. Winning roll is captured
 *      on the entry for UI tooltips.
 *
 * Notes:
 *   - Hero-vs-enemy ties go through the same d6 procedure (no
 *     automatic hero-first short-circuit). The previous "hero
 *     wins ties" rule is superseded by the d6 mechanic.
 *   - The order returned here is STABLE for the lifetime of the
 *     encounter; combat rounds just iterate it from the top each
 *     round. Don't re-roll across rounds.
 *   - When `kind == HERO`, [InitiativeEntry.index] is a slot in
 *     [com.tavisdor.app.party.Party.heroes]; when ENEMY, an index
 *     into the encounter's enemy list.
 */
object Initiative {

    /**
     * @param heroDex   DEX values for each living hero, in party slot order.
     * @param enemyDex  DEX values for each enemy in the encounter, in spawn order.
     * @param rng       RNG used for tie-break d6 rolls. Default = Random.Default;
     *                  pass a seeded instance in tests for deterministic order.
     */
    fun build(
        heroDex: List<Int>,
        enemyDex: List<Int>,
        rng: Random = Random.Default,
    ): List<InitiativeEntry> {
        // Step 1: build the flat list. Heroes first so when we later
        // sort with a stable comparator, the order within an equal
        // DEX block starts predictable - the d6 pass below resolves
        // it for real.
        val raw = buildList {
            heroDex.forEachIndexed { i, dex ->
                add(InitiativeEntry(InitiativeEntry.Kind.HERO, i, dex))
            }
            enemyDex.forEachIndexed { i, dex ->
                add(InitiativeEntry(InitiativeEntry.Kind.ENEMY, i, dex))
            }
        }

        // Step 2: group by DEX, descending. Within each group we
        // either pass through directly (size 1) or run the d6
        // tournament to fix the order.
        val byDex = raw.groupBy { it.dexterity }
            .toSortedMap(compareByDescending { it })
        val result = ArrayList<InitiativeEntry>(raw.size)
        for ((_, tied) in byDex) {
            if (tied.size == 1) {
                result += tied[0]
            } else {
                result += resolveTies(tied, rng)
            }
        }
        return result
    }

    /**
     * Orders [tied] entries (all sharing one DEX value) by a
     * pairwise d6 tournament: each entry rolls; whoever rolls
     * highest goes earliest, then we recurse on the remainder.
     * Re-rolls on draws within a single comparison.
     *
     * This is O(n^2) in entries-per-DEX-bucket which is fine - the
     * party caps at 4, enemies-per-room is small, and ties are the
     * uncommon case anyway.
     */
    private fun resolveTies(
        tied: List<InitiativeEntry>,
        rng: Random,
    ): List<InitiativeEntry> {
        val remaining = ArrayList(tied)
        val ordered = ArrayList<InitiativeEntry>(tied.size)
        while (remaining.size > 1) {
            // Roll once per entry. Winner = highest roll. On draws
            // across the leaderboard, re-roll just the leaders.
            var leaders: MutableList<Pair<InitiativeEntry, Int>> =
                remaining.map { it to rollD6(rng) }.toMutableList()
            while (true) {
                val topRoll = leaders.maxOf { it.second }
                leaders = leaders.filter { it.second == topRoll }.toMutableList()
                if (leaders.size == 1) break
                leaders = leaders.map { it.first to rollD6(rng) }.toMutableList()
            }
            val winner = leaders[0]
            ordered += winner.first.copy(tiebreakRoll = winner.second)
            remaining.remove(winner.first)
        }
        ordered += remaining[0] // last one standing, no roll captured
        return ordered
    }

    private fun rollD6(rng: Random): Int = rng.nextInt(1, 7)
}
