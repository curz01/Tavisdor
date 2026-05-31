package com.tavisdor.app.debug

import com.tavisdor.app.party.LevelProgression

/**
 * Test-only knobs. Centralised here so every cheat is grep-able and
 * easy to flip back to its production default before shipping.
 *
 * NOTHING IN THIS FILE SHOULD STAY ON FOR A RELEASE BUILD.
 */
object DebugConfig {

    /**
     * Level every hero is forced to, regardless of XP. Applied in two
     * places:
     *   - [com.tavisdor.app.party.Hero.spawn]            (new game)
     *   - [com.tavisdor.app.party.Party.fromSaveData]    (continue)
     *
     * Set to [LevelProgression.MAX_LEVEL] (10) right now so the skill
     * picker shows every skill / spell for every class. Restore to
     * `1` for normal progression.
     */
    const val STARTING_HERO_LEVEL: Int = 10

    /**
     * On new game / continue: seeds utility test ingredients and sets
     * party HP/MP to 50% of max for recovery-animation testing.
     */
    const val GRANT_UTILITY_TEST_INGREDIENTS: Boolean = true

    /** Copies of each elemental shard (Flame / Stone / Wind / Hydro) for arrow testing. */
    const val TEST_ELEMENTAL_SHARDS_EACH: Int = 6

    /** True when [STARTING_HERO_LEVEL] is anything other than 1. */
    val isLevelOverrideActive: Boolean
        get() = STARTING_HERO_LEVEL != 1
}
