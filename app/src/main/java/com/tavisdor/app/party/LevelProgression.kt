package com.tavisdor.app.party

/**
 * Hero leveling table. Verbatim from the design doc, but interpreted as
 * a *per-level* curve: [Hero.xp] is "XP earned at the current level" and
 * resets to 0 on level-up. The denominator the UI shows is the delta in
 * the table, not the cumulative value.
 *
 *   Level | Cumulative | Delta from prev level  ( = XP to reach next )
 *   ------+------------+-----------------------
 *      1  |          0 |        -
 *      2  |        500 |      500    <- L1 -> L2
 *      3  |      1,500 |    1,000    <- L2 -> L3
 *      4  |      3,000 |    1,500
 *      5  |      5,000 |    2,000
 *      6  |      7,500 |    2,500
 *      7  |     11,500 |    4,000
 *      8  |     18,000 |    6,500
 *      9  |     28,000 |   10,000
 *     10  |     45,000 |   17,000    <- L9 -> L10
 *
 * Level 10 is the hard cap. A level-10 hero keeps earning XP up to
 * [MAX_LEVEL_XP_CAP] (20,000) and any excess is discarded; they never
 * advance past 10.
 */
object LevelProgression {

    // Cumulative XP needed to BE at each level, index = level - 1.
    // Kept private; callers should go through [xpToNextLevelFrom].
    private val cumulativeXp: IntArray = intArrayOf(
        /* 1  */ 0,
        /* 2  */ 500,
        /* 3  */ 1_500,
        /* 4  */ 3_000,
        /* 5  */ 5_000,
        /* 6  */ 7_500,
        /* 7  */ 11_500,
        /* 8  */ 18_000,
        /* 9  */ 28_000,
        /* 10 */ 45_000,
    )

    /** Hard level ceiling. Heroes cannot advance past this. */
    const val MAX_LEVEL: Int = 10

    /**
     * XP a level-[MAX_LEVEL] hero can bank before further gains are
     * discarded. Used as the progress-bar denominator while at the cap.
     */
    const val MAX_LEVEL_XP_CAP: Int = 20_000

    /**
     * XP needed at [currentLevel] to advance to the next level (delta,
     * not cumulative). For [currentLevel] at or above [MAX_LEVEL] this
     * returns [MAX_LEVEL_XP_CAP] instead - the cap acts as the visual
     * ceiling once leveling is locked.
     */
    fun xpToNextLevelFrom(currentLevel: Int): Int {
        if (currentLevel >= MAX_LEVEL) return MAX_LEVEL_XP_CAP
        val safe = currentLevel.coerceAtLeast(1)
        return cumulativeXp[safe] - cumulativeXp[safe - 1]
    }

    /** True iff [level] has hit the cap and cannot advance further. */
    fun isAtMaxLevel(level: Int): Boolean = level >= MAX_LEVEL

    /**
     * Clamps [xp] to what the hero is allowed to hold at [currentLevel]:
     *   - Below the cap : 0 .. (xpToNextLevelFrom - 1)  (xp == threshold => level-up)
     *   - At the cap    : 0 .. [MAX_LEVEL_XP_CAP]
     *
     * The level-up handler will decide whether to consume the threshold
     * and reset to 0 (per the design, XP resets on level-up). At
     * [MAX_LEVEL] excess is simply truncated.
     */
    fun clampXp(currentLevel: Int, xp: Int): Int {
        if (xp < 0) return 0
        return if (isAtMaxLevel(currentLevel)) {
            xp.coerceAtMost(MAX_LEVEL_XP_CAP)
        } else {
            xp.coerceAtMost(xpToNextLevelFrom(currentLevel))
        }
    }
}
