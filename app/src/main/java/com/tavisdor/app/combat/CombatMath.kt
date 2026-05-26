package com.tavisdor.app.combat

import com.tavisdor.app.enemies.Element
import kotlin.math.max
import kotlin.random.Random

/**
 * Pure-function combat resolvers. No mutation, no UI, no game-state
 * access - given snapshots of attacker / defender stats they return
 * an outcome describing what would happen. Combat orchestration code
 * applies the outcome (deducts HP, posts log lines, plays anims).
 *
 * Two resolution paths, mirroring the design doc:
 *
 *   - [resolveMelee]:  dodge check on DEX, then damage = attack - AC.
 *   - [resolveSpell]:  resist check on INT, then damage scaled by the
 *                      elemental triangle.
 *
 * Both share the same d6 mechanic:
 *   - Natural 6 always hits (ignores stat math).
 *   - Natural 1 always misses (ignores stat math).
 *   - Otherwise: attacker.stat + d6 must STRICTLY exceed defender.stat.
 *     Ties favor the defender (dodge / resist succeeds).
 *
 * INT scaling on spells uses [SPELL_INT_DIVISOR]; integer division
 * means low-INT casters add 0. Tunable from this one constant.
 */
object CombatMath {

    // ----- Tuning constants -----

    /** Sides on the attack die. Hard-coded at 6 per design. */
    const val ATTACK_DIE_SIDES: Int = 6

    /** Natural roll that always hits / always resists check. */
    const val CRIT_ROLL: Int = 6

    /** Natural roll that always misses / always-resists outcome. */
    const val FUMBLE_ROLL: Int = 1

    /**
     * INT divisor for spell damage scaling.
     * Total spell damage before elemental multiplier =
     *   `skill.damage + (attackerInt / SPELL_INT_DIVISOR)`.
     * 2 means a Mage with INT 13 adds +6 to every spell.
     */
    const val SPELL_INT_DIVISOR: Int = 2

    // ----- Public API -----

    /**
     * Resolve a single melee swing.
     *
     * @param attackerDex  attacker's effective DEX (after any temp buffs)
     * @param attackPower  pre-computed attack value (e.g. STR + weapon +
     *                     melee skill bonus). Resolver does NOT add STR
     *                     itself - keep that math at the call site so
     *                     skill modifiers can replace or augment freely.
     * @param defenderDex  defender's effective DEX
     * @param defenderAc   defender's effective AC (after temp buffs /
     *                     debuffs like Armor Break)
     * @param rng          deterministic RNG; pass [Random.Default] in
     *                     prod or a seeded instance for tests.
     */
    fun resolveMelee(
        attackerDex: Int,
        attackPower: Int,
        defenderDex: Int,
        defenderAc: Int,
        rng: Random = Random.Default,
    ): MeleeOutcome {
        val roll = rng.nextInt(1, ATTACK_DIE_SIDES + 1)
        val hit = when (roll) {
            FUMBLE_ROLL -> false
            CRIT_ROLL -> true
            else -> attackerDex + roll > defenderDex
        }
        val damage = if (hit) max(0, attackPower - defenderAc) else 0
        return MeleeOutcome(
            hit = hit,
            naturalRoll = roll,
            damage = damage,
            attackerCheckTotal = attackerDex + roll,
            defenderCheck = defenderDex,
        )
    }

    /**
     * Resolve a single spell hit. The spell's authored [skillDamage]
     * is added to `attackerInt / SPELL_INT_DIVISOR` before the
     * elemental triangle multiplier. AC does NOT subtract from spell
     * damage - resist is binary (full or none).
     *
     * @param attackerInt    caster INT (post-buffs)
     * @param skillDamage    the Skill's authored damage value
     * @param spellElement   the spell's element (never NEUTRAL - it
     *                       wouldn't be a spell otherwise; if you do
     *                       pass NEUTRAL, the multiplier is 100%)
     * @param defenderInt    target's effective INT
     * @param defenderElement target's element (NEUTRAL is fine - it
     *                       just stays at 100%)
     * @param rng            see [resolveMelee]
     */
    fun resolveSpell(
        attackerInt: Int,
        skillDamage: Int,
        spellElement: Element,
        defenderInt: Int,
        defenderElement: Element,
        rng: Random = Random.Default,
    ): SpellOutcome {
        val roll = rng.nextInt(1, ATTACK_DIE_SIDES + 1)
        val hit = when (roll) {
            FUMBLE_ROLL -> false
            CRIT_ROLL -> true
            else -> attackerInt + roll > defenderInt
        }
        val matchup = elementalMatchup(spellElement, defenderElement)
        val pre = skillDamage + (attackerInt / SPELL_INT_DIVISOR)
        val damage = if (hit) (pre * matchup.multiplierPct) / 100 else 0
        return SpellOutcome(
            hit = hit,
            naturalRoll = roll,
            damage = damage,
            matchup = matchup,
            attackerCheckTotal = attackerInt + roll,
            defenderCheck = defenderInt,
            preMultiplierDamage = pre,
        )
    }

    // ----- Disengage -----

    /**
     * Resolve a disengage attempt: the party wants to step out of a
     * tile shared with one or more adjacent enemies. One independent
     * check is rolled per adjacent enemy; ALL must pass for the
     * party to slip away.
     *
     * Per check vs enemy E:
     *   - Roll a d6.
     *   - Natural 1 -> auto-fail (regardless of stats).
     *   - Natural 6 -> auto-succeed (regardless of stats).
     *   - Otherwise: `heroDex + heroInt + d6` must STRICTLY exceed
     *     `enemyDex + enemyInt + (other-adjacent-enemy * d3)`. Ties
     *     favor the defender, matching the
     *     [resolveMelee] / [resolveSpell] convention.
     *
     * The crowding bonus represents the other enemies grabbing /
     * tripping the party as they try to slip past E; with one
     * adjacent enemy it's zero, with two adjacent enemies the
     * single defender gets +1d3, with three it's +2d3.
     *
     * @param heroDexInt    `bestHero.DEX + bestHero.INT` snapshot.
     *                      Caller picks the hero with the highest
     *                      DEX+INT (ties broken arbitrarily).
     * @param enemyDexInts  one entry per adjacent enemy (`DEX+INT`).
     *                      Order matches the [DisengageOutcome.checks]
     *                      list so callers can blame the right enemy
     *                      in log entries.
     * @param rng           injected RNG; seeded for tests, default
     *                      in prod.
     */
    fun resolveDisengage(
        heroDexInt: Int,
        enemyDexInts: List<Int>,
        rng: Random = Random.Default,
    ): DisengageOutcome {
        if (enemyDexInts.isEmpty()) {
            return DisengageOutcome(success = true, checks = emptyList())
        }
        val crowdingDicePerCheck = enemyDexInts.size - 1
        val checks = ArrayList<DisengageCheck>(enemyDexInts.size)
        var allPassed = true
        for ((idx, enemyDexInt) in enemyDexInts.withIndex()) {
            val d6 = rng.nextInt(1, ATTACK_DIE_SIDES + 1)
            var crowdingExtra = 0
            repeat(crowdingDicePerCheck) {
                crowdingExtra += rng.nextInt(1, DISENGAGE_CROWDING_DIE_SIDES + 1)
            }
            val heroSide = heroDexInt + d6
            val enemySide = enemyDexInt + crowdingExtra
            val passed = when (d6) {
                FUMBLE_ROLL -> false
                CRIT_ROLL -> true
                else -> heroSide > enemySide
            }
            if (!passed) allPassed = false
            checks += DisengageCheck(
                enemyIndex = idx,
                d6Roll = d6,
                heroSide = heroSide,
                enemySide = enemySide,
                crowdingExtra = crowdingExtra,
                passed = passed,
            )
        }
        return DisengageOutcome(success = allPassed, checks = checks)
    }

    /** Sides on the crowding die used by [resolveDisengage]. d3 per design. */
    const val DISENGAGE_CROWDING_DIE_SIDES: Int = 3

    /**
     * Compare attacker / defender elements to determine the spell
     * triangle multiplier:
     *   Fire > Earth, Earth > Air, Air > Water, Water > Fire.
     * Returns [ElementalMatchup.NEUTRAL] for any matchup that isn't
     * one of those four directed edges - including all NEUTRAL
     * combatants and mirror-matchups (Fire vs Fire).
     */
    fun elementalMatchup(attacker: Element, defender: Element): ElementalMatchup {
        if (attacker == Element.NEUTRAL || defender == Element.NEUTRAL) {
            return ElementalMatchup.NEUTRAL
        }
        // Directed advantage edges. `attacker beats defender` -> +50%.
        val attackerBeatsDefender = when (attacker) {
            Element.FIRE -> defender == Element.EARTH
            Element.EARTH -> defender == Element.AIR
            Element.AIR -> defender == Element.WATER
            Element.WATER -> defender == Element.FIRE
            Element.NEUTRAL -> false
        }
        if (attackerBeatsDefender) return ElementalMatchup.ADVANTAGE
        val defenderBeatsAttacker = when (defender) {
            Element.FIRE -> attacker == Element.EARTH
            Element.EARTH -> attacker == Element.AIR
            Element.AIR -> attacker == Element.WATER
            Element.WATER -> attacker == Element.FIRE
            Element.NEUTRAL -> false
        }
        if (defenderBeatsAttacker) return ElementalMatchup.DISADVANTAGE
        return ElementalMatchup.NEUTRAL
    }
}

/**
 * Outcome of a single [CombatMath.resolveMelee] call. Pure data;
 * combat orchestration consumes [damage] to mutate HP and uses the
 * rest for log lines / floating numbers.
 */
data class MeleeOutcome(
    /** Did the swing connect? false = dodged or fumbled. */
    val hit: Boolean,
    /** Natural d6 roll (1..6). 1 = always-miss, 6 = always-hit. */
    val naturalRoll: Int,
    /** Damage dealt after AC subtraction. Never negative. */
    val damage: Int,
    /** Attacker's check total (`dex + roll`). Useful for tooltips. */
    val attackerCheckTotal: Int,
    /** Defender's threshold (`dex`). Tie favors the defender. */
    val defenderCheck: Int,
)

/**
 * Outcome of a single [CombatMath.resolveSpell] call. Like
 * [MeleeOutcome] but adds the elemental matchup tag and the
 * pre-multiplier damage value so UI can show
 * "Earth I (5) -> Spear Goblin: x1.0 = 5 dmg".
 */
data class SpellOutcome(
    val hit: Boolean,
    val naturalRoll: Int,
    /** Final damage after the matchup multiplier. 0 on resist. */
    val damage: Int,
    val matchup: ElementalMatchup,
    val attackerCheckTotal: Int,
    val defenderCheck: Int,
    /** Damage BEFORE the matchup multiplier (`skillDamage + INT/N`). */
    val preMultiplierDamage: Int,
)

/**
 * Result of comparing two elements against the spell triangle.
 * [multiplierPct] is an integer percent applied to spell damage:
 *   ADVANTAGE = 150, DISADVANTAGE = 50, NEUTRAL = 100.
 */
enum class ElementalMatchup(val multiplierPct: Int) {
    ADVANTAGE(150),
    DISADVANTAGE(50),
    NEUTRAL(100),
}

/**
 * Outcome of a [CombatMath.resolveDisengage] call. [success] is
 * true iff every per-enemy check passed; [checks] keeps each
 * roll's details (mapped by [DisengageCheck.enemyIndex] back to
 * the input list) so log lines can blame whichever enemy stopped
 * the party.
 */
data class DisengageOutcome(
    val success: Boolean,
    val checks: List<DisengageCheck>,
)

/**
 * One per-enemy disengage roll. Pure data; the controller decides
 * what to do with [passed]. [d6Roll] is exposed so log lines can
 * call out fumbles ("rolled a 1!") and crits ("rolled a 6!")
 * separately from the stat comparison.
 */
data class DisengageCheck(
    /** Index into the input enemy list this check was rolled against. */
    val enemyIndex: Int,
    /** The d6 the hero rolled (1..6). 1 = auto-fail, 6 = auto-succeed. */
    val d6Roll: Int,
    /** `heroDex + heroInt + d6Roll`. */
    val heroSide: Int,
    /** `enemyDex + enemyInt + crowdingExtra`. */
    val enemySide: Int,
    /**
     * Sum of crowding d3 rolls added to the enemy side. Zero with a
     * single adjacent enemy; non-zero when there are 2 or 3.
     */
    val crowdingExtra: Int,
    /** True iff this individual check went the party's way. */
    val passed: Boolean,
)
