package com.tavisdor.app.items

import com.tavisdor.app.combat.CombatMath
import com.tavisdor.app.combat.MeleeOutcome
import com.tavisdor.app.enemies.Enemy
import com.tavisdor.app.enemies.EnemyTemplate
import com.tavisdor.app.party.Hero
import com.tavisdor.app.party.HeroClass
import com.tavisdor.app.skills.Skill
import com.tavisdor.app.skills.SkillCatalog
import kotlin.math.max
import kotlin.random.Random

/**
 * Per-class weapon modifiers from the design chart. [WeaponType.usableBy]
 * gates who may equip; this object applies combat and display rules when
 * a hero wields a weapon their class supports.
 */
object WeaponClassRules {

    const val SPEAR_FIGHTER_RANGE_BONUS: Int = 1
    const val SPEAR_FIGHTER_CLOSE_MISS_PCT: Int = 5
    const val SPEAR_MAGE_CLOSE_MISS_PCT: Int = 10

    const val SWORD_THIEF_DEX_PENALTY: Int = 1
    const val SWORD_THIEF_ATTACK_BONUS: Int = 1

    const val DAGGER_THIEF_BONUS_CRIT_CHANCE: Float = 0.10f

    /** Natural 9 on a connecting Fighter axe swing counts as a crit roll. */
    const val AXE_FIGHTER_CRIT_ROLL_BONUS: Int = 1
    const val AXE_ARCHER_DEX_PENALTY: Int = 1

    const val HAMMER_MAGE_DEX_PENALTY: Int = 1
    const val HAMMER_MAGE_INT_PENALTY: Int = 1

    const val MACE_STUN_PCT: Int = 10
    const val MACE_STUN_BOSS_PCT: Int = 5

    const val BOW_ARCHER_RANGE_BONUS: Int = 1

    const val STAFF_MAGE_MP_DISCOUNT_CHANCE: Float = 0.10f
    const val STAFF_MAGE_MP_DISCOUNT_PCT: Int = 50

    fun classDisplayName(cls: HeroClass): String = when (cls) {
        HeroClass.FIGHTER -> "Fighter"
        HeroClass.MAGE -> "Mage"
        HeroClass.THIEF -> "Thief"
        HeroClass.ARCHER -> "Archer"
    }

    fun usableByLabel(type: WeaponType): String =
        type.usableBy.joinToString(", ") { classDisplayName(it) }

    /** Player-facing special lines for [type], one per class that has a modifier. */
    fun specialLinesFor(type: WeaponType): List<String> = when (type) {
        WeaponType.SPEAR -> listOf(
            "Fighter: range +1; close combat +$SPEAR_FIGHTER_CLOSE_MISS_PCT% miss",
            "Mage: close combat +$SPEAR_MAGE_CLOSE_MISS_PCT% miss",
        )
        WeaponType.SWORD -> listOf(
            "Thief: -$SWORD_THIEF_DEX_PENALTY DEX, +$SWORD_THIEF_ATTACK_BONUS attack",
        )
        WeaponType.DAGGER -> listOf(
            "Thief: +${(DAGGER_THIEF_BONUS_CRIT_CHANCE * 100).toInt()}% critical chance",
        )
        WeaponType.AXE -> listOf(
            "Fighter: +$AXE_FIGHTER_CRIT_ROLL_BONUS to attack die for criticals",
            "Archer: -$AXE_ARCHER_DEX_PENALTY DEX",
        )
        WeaponType.HAMMER -> listOf(
            "Mage: reroll a missed attack; -$HAMMER_MAGE_INT_PENALTY INT, -$HAMMER_MAGE_DEX_PENALTY DEX",
        )
        WeaponType.MACE -> listOf(
            "Fighter: $MACE_STUN_PCT% stun on hit ($MACE_STUN_BOSS_PCT% vs bosses)",
        )
        WeaponType.BOW -> listOf(
            "Archer: range +$BOW_ARCHER_RANGE_BONUS",
        )
        WeaponType.STAFF -> listOf(
            "Mage: ${(STAFF_MAGE_MP_DISCOUNT_CHANCE * 100).toInt()}% chance spells cost " +
                "$STAFF_MAGE_MP_DISCOUNT_PCT% less MP",
        )
        WeaponType.BITE -> emptyList()
    }

    /** Full weapon chart for the inventory / reference panel. */
    fun weaponChartText(): String = buildString {
        WeaponType.entries.filter { it != WeaponType.BITE }.forEach { type ->
            append(type.displayName)
            append(" — ")
            append(usableByLabel(type))
            append('\n')
            specialLinesFor(type).forEach { line ->
                append("  • ")
                append(line)
                append('\n')
            }
            append('\n')
        }
    }.trimEnd()

    fun effectiveRange(hero: Hero, weapon: Weapon): Int {
        var range = weapon.range
        when (weapon.type) {
            WeaponType.SPEAR ->
                if (hero.heroClass == HeroClass.FIGHTER) {
                    range += SPEAR_FIGHTER_RANGE_BONUS
                }
            WeaponType.BOW ->
                if (hero.heroClass == HeroClass.ARCHER) {
                    range += BOW_ARCHER_RANGE_BONUS
                }
            else -> Unit
        }
        return range
    }

    /**
     * Skill reach for UI and combat gates. Fire / Poison / Ice Arrow
     * gain [BOW_ARCHER_RANGE_BONUS] when an Archer wields a bow, same as
     * the basic Attack.
     */
    fun effectiveSkillRange(hero: Hero, skill: Skill): Int {
        if (!SkillCatalog.isArcherElementalArrow(skill.id)) return skill.range
        if (hero.heroClass != HeroClass.ARCHER) return skill.range
        if (hero.weapon1?.type != WeaponType.BOW) return skill.range
        return skill.range + BOW_ARCHER_RANGE_BONUS
    }

    fun effectiveDexterity(hero: Hero, weapon: Weapon?): Int {
        if (weapon == null) return hero.dexterity
        return hero.dexterity + dexModifier(hero.heroClass, weapon.type)
    }

    fun effectiveIntelligence(hero: Hero, weapon: Weapon?): Int {
        if (weapon == null) return hero.intelligence
        return hero.intelligence + intModifier(hero.heroClass, weapon.type)
    }

    fun meleeAttackPowerBonus(hero: Hero, weapon: Weapon?): Int =
        if (weapon != null && hero.heroClass == HeroClass.THIEF && weapon.type == WeaponType.SWORD) {
            SWORD_THIEF_ATTACK_BONUS
        } else {
            0
        }

    /**
     * Spell MP after a possible Staff discount (Mage only). Logs are the
     * caller's responsibility when [logDiscount] is true and cost drops.
     */
    fun adjustedSpellMpCost(hero: Hero, baseMpCost: Int, rng: Random): Int {
        if (baseMpCost <= 0) return 0
        val weapon = hero.weapon1 ?: return baseMpCost
        if (hero.heroClass != HeroClass.MAGE || weapon.type != WeaponType.STAFF) {
            return baseMpCost
        }
        if (rng.nextFloat() >= STAFF_MAGE_MP_DISCOUNT_CHANCE) return baseMpCost
        return (baseMpCost * (100 - STAFF_MAGE_MP_DISCOUNT_PCT) / 100).coerceAtLeast(1)
    }

    fun staffMpDiscountApplied(baseMpCost: Int, adjusted: Int): Boolean =
        adjusted in 1 until baseMpCost

    /**
     * Hero melee to-hit and damage with weapon class modifiers applied.
     * Does not apply skill-specific penalties (sneak attack, archer bow close
     * range, etc.) — those stay in [CombatController].
     */
    fun resolveMelee(
        hero: Hero,
        weapon: Weapon?,
        attackPower: Int,
        target: Enemy,
        rng: Random,
    ): MeleeOutcome {
        val dex = effectiveDexterity(hero, weapon)
        val power = attackPower + meleeAttackPowerBonus(hero, weapon)
        var roll = CombatMath.rollCheckDie(rng)
        val hit = CombatMath.checkSucceeds(dex, target.dexterity, roll)
        var effectiveRoll = roll
        if (hit && weapon != null) {
            effectiveRoll = applyBonusCritRoll(hero.heroClass, weapon.type, roll, rng)
        }
        val baseDamage = if (hit) max(0, power - target.armorClass) else 0
        val damage = CombatMath.scalePhysicalDamageForCritical(baseDamage, effectiveRoll)
        return MeleeOutcome(
            hit = hit,
            naturalRoll = effectiveRoll,
            damage = damage,
            attackerCheckTotal = dex + roll,
            defenderCheck = target.dexterity,
        )
    }

    fun applySpearCloseRangeMissPenalty(
        hero: Hero,
        weapon: Weapon?,
        enemyAdjacentToParty: Boolean,
        outcome: MeleeOutcome,
        rng: Random,
    ): MeleeOutcome {
        val missPct = spearCloseRangeMissPenaltyPct(hero, weapon, enemyAdjacentToParty) ?: return outcome
        return applyMarginalMiss(outcome, missPct, rng)
    }

    fun spearCloseRangeMissPenaltyPct(
        hero: Hero,
        weapon: Weapon?,
        enemyAdjacentToParty: Boolean,
    ): Int? {
        if (weapon?.type != WeaponType.SPEAR || !enemyAdjacentToParty) return null
        return when (hero.heroClass) {
            HeroClass.FIGHTER -> SPEAR_FIGHTER_CLOSE_MISS_PCT
            HeroClass.MAGE -> SPEAR_MAGE_CLOSE_MISS_PCT
            else -> null
        }
    }

    fun hammerMageMayRerollMiss(hero: Hero, weapon: Weapon?): Boolean =
        hero.heroClass == HeroClass.MAGE &&
            weapon?.type == WeaponType.HAMMER

    fun maceStunChancePct(template: EnemyTemplate): Int =
        if (template.isBoss) MACE_STUN_BOSS_PCT else MACE_STUN_PCT

    fun rollMaceStun(hero: Hero, weapon: Weapon?, template: EnemyTemplate, rng: Random): Boolean {
        if (hero.heroClass != HeroClass.FIGHTER || weapon?.type != WeaponType.MACE) {
            return false
        }
        return rng.nextInt(100) < maceStunChancePct(template)
    }

    private fun dexModifier(cls: HeroClass, type: WeaponType): Int = when {
        cls == HeroClass.THIEF && type == WeaponType.SWORD -> -SWORD_THIEF_DEX_PENALTY
        cls == HeroClass.ARCHER && type == WeaponType.AXE -> -AXE_ARCHER_DEX_PENALTY
        cls == HeroClass.MAGE && type == WeaponType.HAMMER -> -HAMMER_MAGE_DEX_PENALTY
        else -> 0
    }

    private fun intModifier(cls: HeroClass, type: WeaponType): Int =
        if (cls == HeroClass.MAGE && type == WeaponType.HAMMER) {
            -HAMMER_MAGE_INT_PENALTY
        } else {
            0
        }

    private fun applyBonusCritRoll(
        cls: HeroClass,
        type: WeaponType,
        roll: Int,
        rng: Random,
    ): Int {
        if (roll == CombatMath.FUMBLE_ROLL || roll == CombatMath.CRIT_ROLL) return roll
        if (cls == HeroClass.FIGHTER && type == WeaponType.AXE &&
            roll + AXE_FIGHTER_CRIT_ROLL_BONUS >= CombatMath.CRIT_ROLL
        ) {
            return CombatMath.CRIT_ROLL
        }
        if (cls == HeroClass.THIEF && type == WeaponType.DAGGER &&
            rng.nextFloat() < DAGGER_THIEF_BONUS_CRIT_CHANCE
        ) {
            return CombatMath.CRIT_ROLL
        }
        return roll
    }

    private fun applyMarginalMiss(
        outcome: MeleeOutcome,
        missPct: Int,
        rng: Random,
    ): MeleeOutcome {
        if (!outcome.hit || missPct <= 0) return outcome
        if (outcome.naturalRoll == CombatMath.FUMBLE_ROLL ||
            outcome.naturalRoll == CombatMath.CRIT_ROLL
        ) {
            return outcome
        }
        if (rng.nextInt(100) >= missPct) return outcome
        return outcome.copy(hit = false, damage = 0)
    }
}
