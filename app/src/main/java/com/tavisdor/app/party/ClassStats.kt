package com.tavisdor.app.party

/**
 * Authored per-class stat progression. Numbers are absolute totals at
 * each level (not increments), verbatim from the design doc:
 *
 *   Level | Mage          | Fighter        | Thief          | Archer
 *   ------+---------------+----------------+----------------+---------------
 *      1  | 1 / 1 / 3     | 2 / 2 / 1      | 1 / 2 / 2      | 1 / 3 / 1
 *      2  | 1 / 1 / 5     | 4 / 2 / 1      | 2 / 3 / 2      | 1 / 4 / 2
 *      3  | 1 / 2 / 7     | 6 / 3 / 1      | 3 / 5 / 2      | 1 / 5 / 3
 *      4  | 2 / 2 / 9     | 7 / 3 / 2      | 4 / 6 / 2      | 2 / 7 / 3
 *      5  | 2 / 3 / 11    | 9 / 4 / 2      | 5 / 8 / 3      | 2 / 8 / 4
 *      6  | 3 / 3 / 13    | 11 / 4 / 2     | 6 / 9 / 3      | 3 / 10 / 5
 *      7  | 3 / 4 / 15    | 12 / 5 / 3     | 7 / 11 / 4     | 3 / 11 / 6
 *      8  | 3 / 4 / 17    | 14 / 5 / 3     | 8 / 12 / 4     | 4 / 13 / 6
 *      9  | 4 / 5 / 18    | 15 / 6 / 4     | 8 / 13 / 5     | 4 / 14 / 7
 *     10  | 4 / 5 / 19    | 17 / 7 / 4     | 9 / 14 / 5     | 5 / 15 / 8
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
        Stats(1, 2, 7),
        Stats(2, 2, 9),
        Stats(2, 3, 11),
        Stats(3, 3, 13),
        Stats(3, 4, 15),
        Stats(3, 4, 17),
        Stats(4, 5, 18),
        Stats(4, 5, 19),
    )

    private val FIGHTER: Array<Stats> = arrayOf(
        Stats(2, 2, 1),
        Stats(4, 2, 1),
        Stats(6, 3, 1),
        Stats(7, 3, 2),
        Stats(9, 4, 2),
        Stats(11, 4, 2),
        Stats(12, 5, 3),
        Stats(14, 5, 3),
        Stats(15, 6, 4),
        Stats(17, 7, 4),
    )

    private val THIEF: Array<Stats> = arrayOf(
        Stats(1, 2, 2),
        Stats(2, 3, 2),
        Stats(3, 5, 2),
        Stats(4, 6, 2),
        Stats(5, 8, 3),
        Stats(6, 9, 3),
        Stats(7, 11, 4),
        Stats(8, 12, 4),
        Stats(8, 13, 5),
        Stats(9, 14, 5),
    )

    private val ARCHER: Array<Stats> = arrayOf(
        Stats(1, 3, 1),
        Stats(1, 4, 2),
        Stats(1, 5, 3),
        Stats(2, 7, 3),
        Stats(2, 8, 4),
        Stats(3, 10, 5),
        Stats(3, 11, 6),
        Stats(4, 13, 6),
        Stats(4, 14, 7),
        Stats(5, 15, 8),
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
