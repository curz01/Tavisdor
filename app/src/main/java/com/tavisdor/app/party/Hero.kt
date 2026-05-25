package com.tavisdor.app.party

import com.tavisdor.app.debug.DebugConfig
import com.tavisdor.app.skills.Skill
import com.tavisdor.app.skills.SkillButton
import com.tavisdor.app.skills.SkillCatalog

/**
 * One member of the party. STR / DEX / INT are NOT stored - they are
 * looked up from [ClassStats] using the hero's class and current level,
 * so the chart is always the single source of truth. Storing them on
 * the hero would let them drift when the design doc is retuned.
 *
 * Stat -> derived value rules (from the design doc):
 *   - 1 STR  =>  +2 Max HP
 *   - 1 DEX  =>  +1% Dodge   (+ initiative ordering, see [Initiative])
 *   - 1 INT  =>  +3 Max MP   (the XP-gain bonus from INT is a *party*
 *                              total, not per-hero - see [Party.xpGainMultiplier])
 *
 * Persistence note: save schema 2 still has [dexterity] and [maxHp] /
 * [maxMp]-style fields, but they are derived now and the saved values
 * are ignored on load. When the schema is next bumped, drop them too.
 */
data class Hero(
    val name: String,
    val heroClass: HeroClass,
    val level: Int = 1,
    /** XP earned at the current level. Resets to 0 on level-up. */
    val xp: Int = 0,
    /** Current HP. Damage is taken / healed against this. Must be <= [maxHp]. */
    val hp: Int = BASE_MAX_HP,
    /** Current MP. Spells / abilities spend against this. Must be <= [maxMp]. */
    val mp: Int = BASE_MAX_MP,
    val armorClass: Int = 10,
    /** Equipment slots; null until the inventory / loot system is wired up. */
    val helmet: String? = null,
    val armor: String? = null,
    val weapon1: String? = null,
    val weapon2: String? = null,
    val boots: String? = null,
) {
    // ----- Core stats (chart-driven, never drift) -----

    /** STR derived from the (class, level) chart. */
    val strength: Int get() = ClassStats.statsFor(heroClass, level).strength

    /** DEX derived from the (class, level) chart. */
    val dexterity: Int get() = ClassStats.statsFor(heroClass, level).dexterity

    /** INT derived from the (class, level) chart. */
    val intelligence: Int get() = ClassStats.statsFor(heroClass, level).intelligence

    // ----- Derived pools (from the Stat-Attributes design chart) -----

    /** Max HP = [BASE_MAX_HP] + 2 per point of [strength]. */
    val maxHp: Int get() = BASE_MAX_HP + strength * STR_HP_PER_POINT

    /** Max MP = [BASE_MAX_MP] + 3 per point of [intelligence]. */
    val maxMp: Int get() = BASE_MAX_MP + intelligence * INT_MP_PER_POINT

    /**
     * Flat dodge chance as a percentage (1% per point of [dexterity]).
     * Combat's to-hit roll subtracts this from the attacker's accuracy.
     * Capped to a sane 90% so a maxed Archer (DEX 17 at L10) still has
     * a small chance to be hit.
     */
    val dodgeChancePct: Int get() = (dexterity * DEX_DODGE_PCT_PER_POINT).coerceAtMost(90)

    /**
     * Maximum equipment weight class this hero can wear without penalty.
     * Each [STR_ARMOR_TIER_THRESHOLD] points of STR unlocks one tier.
     * Placeholder; will be read by the equip flow once it exists.
     */
    val armorTier: Int get() = strength / STR_ARMOR_TIER_THRESHOLD

    // ----- XP / leveling helpers -----

    /**
     * Per-level XP delta needed to advance from the current level. Acts
     * as the denominator the hero detail panel shows ([xp] is the
     * numerator; both reset to 0 on level-up). At [LevelProgression.MAX_LEVEL]
     * this returns [LevelProgression.MAX_LEVEL_XP_CAP] - the bankable
     * ceiling - because the hero can no longer level further.
     */
    val xpForNextLevel: Int get() = LevelProgression.xpToNextLevelFrom(level)

    /** Convenience flag for the UI to dim "level up" affordances at the cap. */
    val isAtMaxLevel: Boolean get() = LevelProgression.isAtMaxLevel(level)

    // ----- Skills (derived from class + level via [SkillCatalog]) -----

    /** Every skill / spell this hero currently knows, in unlock order. */
    val knownSkills: List<Skill> get() = SkillCatalog.knownSkillsFor(heroClass, level)

    /**
     * Subset of [knownSkills] surfaced under a particular action button
     * (ACT / GRD / SPL). Bucketing is derived from [Skill.button].
     */
    fun knownSkillsFor(button: SkillButton): List<Skill> =
        SkillCatalog.knownSkillsFor(heroClass, level, button)

    /**
     * Skill freshly unlocked at this hero's current level, or null if
     * the current level is outside the authored 1..MAX_LEVEL range.
     */
    val newestSkill: Skill? get() = SkillCatalog.unlockedAt(heroClass, level)

    companion object {
        /** Base Max HP before STR is applied. Tuned in one place. */
        const val BASE_MAX_HP: Int = 10

        /** Base Max MP before INT is applied. */
        const val BASE_MAX_MP: Int = 10

        /** From the design chart: 1 STR grants +2 Max HP. */
        const val STR_HP_PER_POINT: Int = 2

        /** From the design chart: 1 INT grants +3 Max MP. */
        const val INT_MP_PER_POINT: Int = 3

        /** From the design chart: 1 DEX grants +1% dodge. */
        const val DEX_DODGE_PCT_PER_POINT: Int = 1

        /**
         * Placeholder gate for the "heavier armor" rule. Every N points
         * of STR unlocks one armor tier (0 = cloth, 1 = leather, ...).
         * Real numbers should come from the armor-requirements chart.
         */
        const val STR_ARMOR_TIER_THRESHOLD: Int = 4

        /** Class-flavored starting armor class. Placeholder until an AC chart lands. */
        fun defaultArmorClassFor(cls: HeroClass): Int = when (cls) {
            HeroClass.FIGHTER -> 14
            HeroClass.ARCHER -> 12
            HeroClass.THIEF -> 11
            HeroClass.MAGE -> 10
        }

        /**
         * Builds a freshly-spawned hero of [cls] with hp/mp filled to
         * their derived maxes. Use this for character creation; the
         * raw constructor is for load / copy / test code that wants
         * to override individual fields.
         *
         * Spawn level normally is 1; while [DebugConfig.STARTING_HERO_LEVEL]
         * is non-default the hero spawns at that level instead so the
         * skill picker can be exercised end-to-end without grinding.
         */
        fun spawn(name: String, cls: HeroClass): Hero {
            val lvl = DebugConfig.STARTING_HERO_LEVEL
                .coerceIn(1, LevelProgression.MAX_LEVEL)
            val proto = Hero(
                name = name,
                heroClass = cls,
                level = lvl,
                armorClass = defaultArmorClassFor(cls),
            )
            return proto.copy(hp = proto.maxHp, mp = proto.maxMp)
        }
    }
}
