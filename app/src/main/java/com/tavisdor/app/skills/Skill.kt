package com.tavisdor.app.skills

import com.tavisdor.app.enemies.Element
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
 * Bucketing rule (per design): collapse spells into ACTION, keep
 * GUARD for prepared / passive / defensive skills.
 *   - [buttonOverride] non-null         => exactly that bucket
 *   - else [castType] == ACTIVE         => [SkillButton.ACTION]
 *     (covers basic Attack, every damage skill, and every spell -
 *     mana cost no longer steers bucketing on its own)
 *   - else (PREPARE / PASSIVE)          => [SkillButton.GUARD]
 *
 * The override exists for skills that don't fit the cast-type
 * heuristic: the universal Defend is ACTIVE (fires this turn) but
 * lives under GUARD because it's defensive, not offensive.
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
    /**
     * Optional explicit bucket. When non-null this skill ALWAYS
     * surfaces under [buttonOverride] regardless of [castType] -
     * use it for ACTIVE skills that should still live under GUARD
     * (defensive moves like Defend) or PREPARE skills the designer
     * wants surfaced as a direct ACTION. Leave null to let the
     * cast-type heuristic decide.
     */
    val buttonOverride: SkillButton? = null,
    /**
     * Authored base damage. Interpretation depends on whether this
     * skill is a spell:
     *   - Spell ([element] != null): full spell damage before the
     *     resist check + elemental multiplier. Caster INT is added
     *     on top at resolve time (see CombatMath).
     *   - Melee skill ([element] == null) with non-null [damage]:
     *     ADDS to the basic-attack damage. e.g. Heavy Strike's
     *     "+2d3" is authored as the average (4); resolution adds
     *     that to STR + weapon damage.
     *   - null: skill doesn't deal damage on its own
     *     (Heal, buffs, passives, prepare-only setup moves).
     */
    val damage: Int? = null,
    /**
     * Elemental tag for spell damage. null means this skill is NOT
     * an elemental spell - it resolves through the melee path
     * (dodge check) even if it costs MP and reads like magic.
     *
     * Heal / utility / passive skills with no damage typically have
     * [element] == null because they aren't subject to the resist
     * or elemental triangle math.
     */
    val element: Element? = null,
) {
    /**
     * Which hero-panel button surfaces this skill. Computed from
     * [buttonOverride] (when set) or [castType] (when null), so the
     * rule lives in exactly one place.
     */
    val button: SkillButton
        get() = buttonOverride ?: when (castType) {
            SkillCastType.ACTIVE -> SkillButton.ACTION
            SkillCastType.PREPARE, SkillCastType.PASSIVE -> SkillButton.GUARD
        }

    /** Convenience for the UI: passives are listed under GUARD but don't behave like a prepared action. */
    val isPassive: Boolean get() = castType == SkillCastType.PASSIVE

    /**
     * True iff this skill resolves through the SPELL combat path
     * (resist check on INT, elemental triangle multiplier). Driven
     * by [element] - the catalog tags every spell with its element
     * even when [mpCost] is 0 (e.g. arrow elementals).
     */
    val isSpell: Boolean get() = element != null
}
