package com.tavisdor.app.combat

import kotlin.random.Random

/**
 * Per-encounter hate engine. Each [com.tavisdor.app.enemies.Enemy]
 * tracks how angry it is at every party member on a 1..5 scale; the
 * AI targets the highest-hate hero, ties broken by a coin flip.
 *
 * Rules (per the design brief):
 *   - Every hero starts each fresh combat at [HATE_DEFAULT] = 1.
 *   - Mage casts a heal spell -> every enemy's hate toward the mage
 *     increases by +2 (clamped to [HATE_MAX]).
 *   - Hero deals damage to an enemy -> that hero's damage tally
 *     against that enemy increases. At the start of each enemy's
 *     turn the hero with the highest tally since this enemy's
 *     LAST turn gets +1 hate from this enemy; the tally then
 *     resets.
 *   - Enemy lands a successful attack on the hero with highest
 *     hate -> that hero's hate (from this enemy) drops back to 1.
 *   - Values are hard-clamped to [HATE_MIN]..[HATE_MAX].
 *
 * State is keyed by [combat.enemies] list index and [party.heroes]
 * slot (0..3). Both lists are stable for the lifetime of a
 * [Combat], so a plain 2D `IntArray` is enough - no need for
 * per-instance maps.
 *
 * The tracker is intentionally pure data + thin mutators:
 *   - No knowledge of combat resolution or animation.
 *   - No randomness baked in - tie-breaking RNG is injected by the
 *     caller (the combat controller) so [Combat]'s deterministic
 *     seeding doesn't get split between two RNG sources.
 */
class HateTracker(
    /** Number of enemy slots in the encounter. Fixed at construction. */
    val enemyCount: Int,
    /** Party size (always 4 today, but parameterized for tests). */
    val partySize: Int,
) {
    /**
     * `hate[enemyIdx][heroSlot]` = how much enemy [enemyIdx] hates
     * hero [heroSlot] right now. Starts at [HATE_DEFAULT] for every
     * pair; mutators clamp to [HATE_MIN]..[HATE_MAX].
     */
    private val hate: Array<IntArray> = Array(enemyCount) {
        IntArray(partySize) { HATE_DEFAULT }
    }

    /**
     * `damageSinceLastTurn[enemyIdx][heroSlot]` = cumulative damage
     * dealt by hero [heroSlot] to enemy [enemyIdx] since enemy
     * [enemyIdx]'s LAST turn. Reset to zero in [resetDamageTracker]
     * when its turn comes back around.
     */
    private val damageSinceLastTurn: Array<IntArray> = Array(enemyCount) {
        IntArray(partySize) { 0 }
    }

    /**
     * Current hate value of enemy [enemyIdx] toward hero [heroSlot].
     * Returns [HATE_DEFAULT] for out-of-range indices so callers can
     * safely query during teardown / mismatched lists.
     */
    fun hateFor(enemyIdx: Int, heroSlot: Int): Int {
        if (enemyIdx !in 0 until enemyCount) return HATE_DEFAULT
        if (heroSlot !in 0 until partySize) return HATE_DEFAULT
        return hate[enemyIdx][heroSlot]
    }

    /**
     * Overwrites the hate value, clamping to [HATE_MIN]..[HATE_MAX].
     * Out-of-range indices are silently ignored.
     */
    fun setHate(enemyIdx: Int, heroSlot: Int, value: Int) {
        if (enemyIdx !in 0 until enemyCount) return
        if (heroSlot !in 0 until partySize) return
        hate[enemyIdx][heroSlot] = value.coerceIn(HATE_MIN, HATE_MAX)
    }

    /** Convenience: hate += [delta], clamped. */
    fun bumpHate(enemyIdx: Int, heroSlot: Int, delta: Int) {
        setHate(enemyIdx, heroSlot, hateFor(enemyIdx, heroSlot) + delta)
    }

    /**
     * Fighter Taunt: each enemy in [enemyIndices] gains +[TAUNT_CASTER_DELTA]
     * hate toward [casterSlot] and -[TAUNT_OTHERS_DELTA] toward every other
     * living hero ([isHeroAlive]). Dead heroes are skipped.
     */
    fun applyTaunt(
        casterSlot: Int,
        enemyIndices: Iterable<Int>,
        isHeroAlive: (heroSlot: Int) -> Boolean,
    ) {
        if (casterSlot !in 0 until partySize) return
        for (enemyIdx in enemyIndices) {
            if (enemyIdx !in 0 until enemyCount) continue
            bumpHate(enemyIdx, casterSlot, TAUNT_CASTER_DELTA)
            for (heroSlot in 0 until partySize) {
                if (heroSlot == casterSlot) continue
                if (!isHeroAlive(heroSlot)) continue
                bumpHate(enemyIdx, heroSlot, TAUNT_OTHERS_DELTA)
            }
        }
    }

    /**
     * Accumulates [amount] damage dealt by hero [heroSlot] against
     * enemy [enemyIdx] this round. Non-positive amounts are ignored
     * so a 0-damage hit (AC fully soaked) doesn't bump aggro.
     */
    fun recordDamage(enemyIdx: Int, heroSlot: Int, amount: Int) {
        if (amount <= 0) return
        if (enemyIdx !in 0 until enemyCount) return
        if (heroSlot !in 0 until partySize) return
        damageSinceLastTurn[enemyIdx][heroSlot] += amount
    }

    /**
     * Picks the hero slot tied at the top damage count against
     * enemy [enemyIdx]. Returns null if no damage was recorded
     * since this enemy's last turn (no-one to aggro on this round).
     * Ties broken by [rng]; the caller is the combat controller
     * so the encounter's deterministic RNG is reused.
     */
    fun consumeTopDamager(enemyIdx: Int, rng: Random): Int? {
        if (enemyIdx !in 0 until enemyCount) return null
        val row = damageSinceLastTurn[enemyIdx]
        var max = 0
        for (i in row.indices) if (row[i] > max) max = row[i]
        if (max == 0) return null
        val tied = ArrayList<Int>(row.size)
        for (i in row.indices) if (row[i] == max) tied += i
        return if (tied.size == 1) tied[0] else tied.random(rng)
    }

    /**
     * Wipes enemy [enemyIdx]'s damage tally so the next round
     * starts from zero. Called at the start of that enemy's turn,
     * right after [consumeTopDamager], so the "since-last-turn"
     * window stays accurate.
     */
    fun resetDamageTracker(enemyIdx: Int) {
        if (enemyIdx !in 0 until enemyCount) return
        val row = damageSinceLastTurn[enemyIdx]
        for (i in row.indices) row[i] = 0
    }

    /**
     * Hero slot enemy [enemyIdx] will strike at this beat. Picks
     * the hero with the highest hate; ties broken by [rng]. The
     * [isAlive] predicate filters dead heroes out of consideration
     * so KO'd party members don't keep drawing attacks.
     *
     * Returns null when there are no living heroes left.
     */
    /**
     * True when [heroSlot]'s hate from [enemyIdx] is strictly greater than
     * every other living hero's hate from that enemy (ties do not count).
     */
    fun isStrictlyHighestHateToward(
        enemyIdx: Int,
        heroSlot: Int,
        isHeroAlive: (heroSlot: Int) -> Boolean,
    ): Boolean {
        if (enemyIdx !in 0 until enemyCount) return false
        if (heroSlot !in 0 until partySize) return false
        val self = hateFor(enemyIdx, heroSlot)
        for (other in 0 until partySize) {
            if (other == heroSlot) continue
            if (!isHeroAlive(other)) continue
            if (hateFor(enemyIdx, other) >= self) return false
        }
        return true
    }

    fun pickTarget(
        enemyIdx: Int,
        isAlive: (heroSlot: Int) -> Boolean,
        rng: Random,
    ): Int? {
        if (enemyIdx !in 0 until enemyCount) return null
        val row = hate[enemyIdx]
        var max = HATE_MIN - 1
        for (i in row.indices) {
            if (!isAlive(i)) continue
            if (row[i] > max) max = row[i]
        }
        if (max < HATE_MIN) return null
        val tied = ArrayList<Int>(row.size)
        for (i in row.indices) {
            if (!isAlive(i)) continue
            if (row[i] == max) tied += i
        }
        return when {
            tied.isEmpty() -> null
            tied.size == 1 -> tied[0]
            else -> tied.random(rng)
        }
    }

    companion object {
        /** Lowest possible hate value; nobody is invisible to enemies. */
        const val HATE_MIN: Int = 1

        /** Hard cap so the UI's 5-icon palette always has a sprite. */
        const val HATE_MAX: Int = 5

        /** Default at combat start unless an enemy template overrides. */
        const val HATE_DEFAULT: Int = 1

        /** Fighter Taunt: hate delta applied to the taunting hero per enemy. */
        const val TAUNT_CASTER_DELTA: Int = 2

        /** Fighter Taunt: hate delta applied to each other living hero per enemy. */
        const val TAUNT_OTHERS_DELTA: Int = -1
    }
}
