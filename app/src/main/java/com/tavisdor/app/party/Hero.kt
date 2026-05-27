package com.tavisdor.app.party

import com.tavisdor.app.debug.DebugConfig
import com.tavisdor.app.items.Weapon
import com.tavisdor.app.skills.Skill
import com.tavisdor.app.skills.SkillButton
import com.tavisdor.app.skills.SkillCatalog

/** Offensive column in the skill-assignment panel (ACTION bucket). */
fun Hero.offensiveSkillsForAssign(): List<Skill> =
    knownSkills.filter {
        it.button == SkillButton.ACTION &&
            it.id !in SkillCatalog.ASSIGN_PASSIVE_COLUMN_SKILL_IDS
    }

/** Defensive column: GUARD bucket excluding passives and utility skills. */
fun Hero.defensiveSkillsForAssign(): List<Skill> =
    knownSkills.filter {
        it.button == SkillButton.GUARD &&
            !it.isPassive &&
            it.id !in SkillCatalog.ASSIGN_PASSIVE_COLUMN_SKILL_IDS
    }

/** Passive column: always-on passives plus out-of-combat utility skills. */
fun Hero.passiveSkillsForAssign(): List<Skill> =
    knownSkills.filter {
        it.isPassive || it.id in SkillCatalog.ASSIGN_PASSIVE_COLUMN_SKILL_IDS
    }

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
    /**
     * Cosmetic gender pick made on the class-select screen.
     * Drives portrait sprite lookup ([com.tavisdor.app.render.HeroPanelRenderer])
     * and the auto-name pool only - never affects stats, AC,
     * damage, dodge, or any other mechanical value.
     */
    val gender: Gender = Gender.MALE,
    /**
     * Hero level. Mutable because combat KO penalties can drop a
     * hero a level (see [applyDeathPenalty]). Identity is still
     * (name, class); equality compares full state at the moment of
     * the call, which is appropriate for save/load semantics.
     */
    var level: Int = 1,
    /** XP earned at the current level. Resets to 0 on level-up. */
    var xp: Int = 0,
    /** Current HP. Damage is taken / healed against this. Must be <= [maxHp]. */
    var hp: Int = BASE_MAX_HP,
    /** Current MP. Spells / abilities spend against this. Must be <= [maxMp]. */
    var mp: Int = BASE_MAX_MP,
    val armorClass: Int = 10,
    /** Equipment slots; armor pieces remain string placeholders
     *  until the armor-loot system lands. Weapon slots are typed
     *  via [Weapon] so combat can read range / damage off the
     *  equipped item; [weapon1] is the primary swing / shot and
     *  [weapon2] is reserved for off-hand / dual-wield. Crude
     *  starters are issued by [Hero.spawn] / [Party.fromSaveData]
     *  - heroes are never barehanded once they leave class-select. */
    val helmet: String? = null,
    val armor: String? = null,
    val weapon1: Weapon? = null,
    val weapon2: Weapon? = null,
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
     * Capped to a sane 90% so a max-DEX hero at L10 still has
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

    /**
     * The hero's current "Attack" option, with [Skill.displayName]
     * and [Skill.range] rewritten from equipped [weapon1]. Shows
     * `Attack (Sword)` etc. using [WeaponType.displayName] only
     * (no tier prefix like "Crude"). A crude bow bumps range to
     * [Weapon.CRUDE_BOW_RANGE] (3); melee weapons stay at 1.
     */
    val basicAttackSkill: Skill
        get() {
            val base = SkillCatalog.basicAttackFor(heroClass)
            val w = weapon1 ?: return base
            return base.copy(
                displayName = SkillCatalog.basicAttackDisplayName(w.type),
                range = w.range,
            )
        }

    /**
     * Every skill / spell this hero currently knows, in unlock
     * order. The universal basic Attack is patched in with the
     * weapon-aware range (see [basicAttackSkill]) so the skill
     * picker and the hero detail panel both show bow range for an
     * Archer with a bow equipped.
     */
    val knownSkills: List<Skill> get() = applyBasicAttackRange(
        SkillCatalog.knownSkillsFor(heroClass, level),
    )

    /**
     * Subset of [knownSkills] surfaced under a particular action button
     * (ACT / GRD / SPL). Bucketing is derived from [Skill.button].
     */
    fun knownSkillsFor(button: SkillButton): List<Skill> = applyBasicAttackRange(
        SkillCatalog.knownSkillsFor(heroClass, level, button),
    )

    /**
     * Rewrites the [SkillCatalog.BASIC_ATTACK_ID] entry in [list]
     * with the weapon-aware range from [basicAttackSkill]. Every
     * other entry passes through unchanged. Returning the same
     * list instance when no substitution happens keeps the hot
     * path allocation-free for heroes whose weapon already
     * matches the default range.
     */
    private fun applyBasicAttackRange(list: List<Skill>): List<Skill> {
        if (weapon1 == null) return list
        val ba = basicAttackSkill
        return list.map { if (it.id == SkillCatalog.BASIC_ATTACK_ID) ba else it }
    }

    /**
     * Skill freshly unlocked at this hero's current level, or null if
     * the current level is outside the authored 1..MAX_LEVEL range.
     */
    val newestSkill: Skill? get() = SkillCatalog.unlockedAt(heroClass, level)

    // ----- Combat runtime helpers -----

    /** True iff this hero has positive HP. KO'd heroes wait for revive. */
    val isAlive: Boolean get() = hp > 0

    /**
     * Subtracts [amount] from [hp], floored at 0. No-op when [amount]
     * is non-positive (use [heal] for healing). Returns the actual
     * damage taken so UI / log code can show floating numbers.
     */
    fun takeDamage(amount: Int): Int {
        if (amount <= 0) return 0
        val before = hp
        hp = (hp - amount).coerceAtLeast(0)
        return before - hp
    }

    /**
     * Adds [amount] to [hp], capped at [maxHp]. Refuses to revive a
     * KO'd hero (`hp == 0`) - heal-on-dead is a separate ritual per
     * design ("Cannot heal dead"). Returns the actual HP restored.
     */
    fun heal(amount: Int): Int {
        if (amount <= 0 || hp == 0) return 0
        val before = hp
        hp = (hp + amount).coerceAtMost(maxHp)
        return hp - before
    }

    /** Spends [cost] mana. No-op if [cost] is non-positive. */
    fun spendMana(cost: Int): Int {
        if (cost <= 0) return 0
        val before = mp
        mp = (mp - cost).coerceAtLeast(0)
        return before - mp
    }

    /** Restores HP / MP to their derived maxes. Used by party-wipe respawn. */
    fun restoreFull() {
        hp = maxHp
        mp = maxMp
    }

    /**
     * Banks [amount] of XP, applying level-ups when the running
     * total crosses the per-level threshold (see [LevelProgression]).
     * Returns the number of levels gained (0 when [amount] just
     * tops up the current level without crossing).
     *
     * Mechanics:
     *   - On crossing the threshold, [xp] resets to 0 and the
     *     EXCESS rolls into the new level. So a hero at L2 / 999 xp
     *     (threshold 1000) gaining 600 xp ends up at L3 with 599 xp,
     *     not L3 with 0.
     *   - At [LevelProgression.MAX_LEVEL] the hero stops leveling;
     *     surplus XP is capped at [LevelProgression.MAX_LEVEL_XP_CAP]
     *     and anything past that is discarded.
     *   - No HP/MP top-up on level-up (intentional - separate from
     *     the leveling math; if we ever auto-heal on level it goes
     *     here in one place).
     */
    fun applyXpGain(amount: Int): Int {
        if (amount <= 0) return 0
        var remaining = amount
        var levelsGained = 0
        while (remaining > 0) {
            if (LevelProgression.isAtMaxLevel(level)) {
                xp = (xp + remaining).coerceAtMost(LevelProgression.MAX_LEVEL_XP_CAP)
                break
            }
            val threshold = LevelProgression.xpToNextLevelFrom(level)
            val needed = threshold - xp
            if (remaining < needed) {
                xp += remaining
                remaining = 0
            } else {
                remaining -= needed
                xp = 0
                level += 1
                levelsGained += 1
            }
        }
        return levelsGained
    }

    /**
     * Applies the on-KO penalty: lose 10% of the XP needed to clear
     * the CURRENT level. If that drops [xp] below 0, the hero loses
     * a level and the residual deficit is re-applied against the
     * new level's threshold (recursive until [xp] >= 0 or [level] is
     * back at 1).
     *
     * Already at level 1 with xp=0 is the floor - the penalty can't
     * push past that.
     */
    fun applyDeathPenalty() {
        val penalty = (LevelProgression.xpToNextLevelFrom(level) * DEATH_XP_PENALTY_PCT) / 100
        var remaining = penalty
        while (remaining > 0) {
            if (xp >= remaining) {
                xp -= remaining
                remaining = 0
            } else {
                remaining -= xp
                xp = 0
                if (level <= 1) break // can't drop below L1
                level -= 1
                // Front-load the new level's xp to absorb the residual
                // deficit on the next loop iteration.
                xp = LevelProgression.xpToNextLevelFrom(level)
            }
        }
    }

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
         * Hero KO penalty: percentage of the current level's XP
         * threshold to deduct from [xp]. The combat doc says 10%;
         * tune here if play-test feels too punishing.
         */
        const val DEATH_XP_PENALTY_PCT: Int = 10

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
        fun spawn(name: String, cls: HeroClass, gender: Gender = Gender.MALE): Hero {
            val lvl = DebugConfig.STARTING_HERO_LEVEL
                .coerceIn(1, LevelProgression.MAX_LEVEL)
            val proto = Hero(
                name = name,
                heroClass = cls,
                gender = gender,
                level = lvl,
                armorClass = defaultArmorClassFor(cls),
                // Crude starter so the archer has range from
                // turn one and the other classes have a flavor
                // weapon to swing. Issuing it here keeps both
                // new-game and debug-spawn paths consistent.
                weapon1 = Weapon.crudeStarterFor(cls),
            )
            return proto.copy(hp = proto.maxHp, mp = proto.maxMp)
        }
    }
}
