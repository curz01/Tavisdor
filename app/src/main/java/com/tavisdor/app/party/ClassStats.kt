package com.tavisdor.app.party

/**
 * Authored per-class stat progression. Numbers are absolute totals at
 * each level (not increments), verbatim from the design doc:
 *
 *   Level | Mage          | Fighter        | Thief          | Archer
 *   ------+---------------+----------------+----------------+---------------
 *      1  | 1 / 1 / 3     | 2 / 2 / 1      | 1 / 2 / 2      | 1 / 3 / 1
 *      2  | 1 / 1 / 5     | 4 / 2 / 1      | 2 / 3 / 2      | 1 / 5 / 1
 *      3  | 2 / 2 / 6     | 5 / 3 / 2      | 3 / 5 / 2      | 2 / 6 / 2
 *      4  | 2 / 2 / 8     | 7 / 3 / 2      | 4 / 6 / 2      | 2 / 8 / 2
 *      5  | 3 / 3 / 9     | 8 / 4 / 3      | 5 / 8 / 2      | 3 / 9 / 3
 *      6  | 3 / 3 / 11    | 10 / 4 / 3     | 6 / 9 / 3      | 3 / 11 / 3
 *      7  | 4 / 4 / 12    | 11 / 5 / 4     | 7 / 11 / 3     | 4 / 12 / 4
 *      8  | 4 / 4 / 14    | 13 / 5 / 4     | 8 / 12 / 3     | 4 / 14 / 4
 *      9  | 5 / 5 / 15    | 14 / 6 / 5     | 9 / 14 / 3     | 5 / 15 / 5
 *     10  | 5 / 5 / 17    | 16 / 6 / 5     | 10 / 15 / 3    | 5 / 17 / 5
 *
 * Values are read STR / DEX / INT in that order. Stats outside this
 * table (level > [LevelProgression.MAX_LEVEL]) cannot happen because
 * heroes cap at level 10 - if a level somehow slips through, we
 * clamp to the highest authored row instead of crashing.
 *
 * Hero stats are derived from this table, never stored on [Hero], so
 * the chart is the single source of truth.
 */
object ClassStats {

    /** STR / DEX / INT for one (class, level) cell of the chart. */
    data class Stats(val strength: Int, val dexterity: Int, val intelligence: Int)

    // Index = level - 1. All four tables must have at least
    // LevelProgression.MAX_LEVEL rows.
    private val MAGE: Array<Stats> = arrayOf(
        Stats(1, 1, 3),
        Stats(1, 1, 5),
        Stats(2, 2, 6),
        Stats(2, 2, 8),
        Stats(3, 3, 9),
        Stats(3, 3, 11),
        Stats(4, 4, 12),
        Stats(4, 4, 14),
        Stats(5, 5, 15),
        Stats(5, 5, 17),
    )

    private val FIGHTER: Array<Stats> = arrayOf(
        Stats(2, 2, 1),
        Stats(4, 2, 1),
        Stats(5, 3, 2),
        Stats(7, 3, 2),
        Stats(8, 4, 3),
        Stats(10, 4, 3),
        Stats(11, 5, 4),
        Stats(13, 5, 4),
        Stats(14, 6, 5),
        Stats(16, 6, 5),
    )

    private val THIEF: Array<Stats> = arrayOf(
        Stats(1, 2, 2),
        Stats(2, 3, 2),
        Stats(3, 5, 2),
        Stats(4, 6, 2),
        Stats(5, 8, 2),
        Stats(6, 9, 3),
        Stats(7, 11, 3),
        Stats(8, 12, 3),
        Stats(9, 14, 3),
        Stats(10, 15, 3),
    )

    private val ARCHER: Array<Stats> = arrayOf(
        Stats(1, 3, 1),
        Stats(1, 5, 1),
        Stats(2, 6, 2),
        Stats(2, 8, 2),
        Stats(3, 9, 3),
        Stats(3, 11, 3),
        Stats(4, 12, 4),
        Stats(4, 14, 4),
        Stats(5, 15, 5),
        Stats(5, 17, 5),
    )

    init {
        // Defensive: keep the tables in lock-step with the level cap.
        // Failing here means the design doc and code disagree.
        require(MAGE.size >= LevelProgression.MAX_LEVEL)
        require(FIGHTER.size >= LevelProgression.MAX_LEVEL)
        require(THIEF.size >= LevelProgression.MAX_LEVEL)
        require(ARCHER.size >= LevelProgression.MAX_LEVEL)
    }

    /**
     * STR / DEX / INT for [cls] at [level]. Level is clamped to the
     * authored range (1 .. table-size) so callers never need to guard
     * against the cap themselves.
     */
    fun statsFor(cls: HeroClass, level: Int): Stats {
        val table = tableFor(cls)
        val idx = (level - 1).coerceIn(0, table.size - 1)
        return table[idx]
    }

    private fun tableFor(cls: HeroClass): Array<Stats> = when (cls) {
        HeroClass.MAGE -> MAGE
        HeroClass.FIGHTER -> FIGHTER
        HeroClass.THIEF -> THIEF
        HeroClass.ARCHER -> ARCHER
    }
}
