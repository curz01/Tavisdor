package com.tavisdor.app.skills

import com.tavisdor.app.party.HeroClass

/**
 * How a skill / spell resolves once selected.
 *  - [ACTIVE]  : fires the same turn it's chosen.
 *  - [PREPARE] : occupies the hero for one or more setup turns before
 *                the actual effect resolves. Exact turn count TBD.
 *  - [PASSIVE] : always-on; the hero never explicitly "uses" it. Lives
 *                under the GRD button as a status indicator only.
 */
enum class SkillCastType { ACTIVE, PREPARE, PASSIVE }

/**
 * Single learnable ability (skill OR spell) belonging to one [HeroClass].
 * Most fields come straight from the design doc; richer effect modeling
 * (damage formulas, hate deltas, status timers, item gates) is layered
 * on top as those systems land. For now the canonical effect text lives
 * in [description].
 *
 * Bucketing rule (per design): mana cost trumps cast type.
 *   - [mpCost] > 0                      => [SkillButton.SPELLS]
 *   - else [castType] == ACTIVE         => [SkillButton.ACTION]
 *   - else (PREPARE / PASSIVE)          => [SkillButton.GUARD]
 */
data class Skill(
    /**
     * Stable lookup key, lowercase snake_case. Used by saves, by the
     * combat command UI, and by per-skill effect handlers. Never shown
     * to the player; use [displayName] for that.
     *
     * Format: `<class>_<skill_name>[_<tier>]`, e.g. `mage_fire_1`,
     * `fighter_heavy_strike`.
     */
    val id: String,
    /** Player-facing name as written in the description doc. */
    val displayName: String,
    val heroClass: HeroClass,
    val castType: SkillCastType,
    /**
     * Range in dungeon cells.
     *   R0 = self / current tile (no target)
     *   R1 = adjacent tile
     *   Rn = up to n tiles away (line-of-sight rules TBD per skill).
     */
    val range: Int,
    /**
     * Hero level at which this skill becomes known. The progression
     * curve grants exactly one new skill per level (1..10) per class.
     */
    val unlockLevel: Int,
    /** Mana cost paid when this skill is used. 0 if free. */
    val mpCost: Int = 0,
    /**
     * Whether using this skill consumes the hero's main action for the
     * turn. `true` (default) for normal skills. Skills whose
     * description says "does not cost an action" set this to `false`,
     * which lets the hero still take a regular action this turn AND
     * drives the yellow-border selection state in the picker UI.
     */
    val costsAction: Boolean = true,
    /**
     * Free-form effect text, verbatim from the design doc. Mechanical
     * details (damage dice, hate delta, status durations) will be
     * parsed out into structured fields when the combat system is
     * fleshed out enough to consume them.
     */
    val description: String = "",
) {
    /**
     * Which hero-panel button surfaces this skill. Computed - never
     * stored - so the rule lives in exactly one place.
     */
    val button: SkillButton
        get() = when {
            mpCost > 0 -> SkillButton.SPELLS
            castType == SkillCastType.ACTIVE -> SkillButton.ACTION
            else -> SkillButton.GUARD
        }

    /** Convenience for the UI: passives are listed under GUARD but don't behave like a prepared action. */
    val isPassive: Boolean get() = castType == SkillCastType.PASSIVE
}
