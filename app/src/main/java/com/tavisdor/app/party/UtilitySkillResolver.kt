package com.tavisdor.app.party

import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.dungeon.Floor
import com.tavisdor.app.items.Ingredient
import com.tavisdor.app.items.IngredientCategory
import com.tavisdor.app.items.Potion
import com.tavisdor.app.items.Inventory
import com.tavisdor.app.items.WeaponType
import com.tavisdor.app.render.UtilityCastFxCatalog
import com.tavisdor.app.render.UtilityCastMotion
import com.tavisdor.app.render.WeaponFxKind
import com.tavisdor.app.render.WeaponFxRequest
import com.tavisdor.app.skills.Skill
import com.tavisdor.app.skills.SkillCatalog

/**
 * Out-of-combat utility skills (camp, rest, cooking, make potion).
 */
object UtilitySkillResolver {

    data class RecoveryTotals(
        val totalHp: Int,
        val totalMp: Int,
    )

    data class CastPlan(
        val ingredient: Ingredient,
        val fxRequest: WeaponFxRequest,
        val recovery: UtilityRecoverySession?,
        /** Set when Make Potion finishes (no HP/MP ticks during the cast). */
        val potionToGrant: Potion? = null,
        /** Pre-computed recovery for the combat log summary line. */
        val recoveryTotals: RecoveryTotals? = null,
    )

    fun isUtility(skill: Skill): Boolean =
        UtilityCastFxCatalog.isUtilitySkill(skill.id)

    /**
     * Hard requirements only (ingredient, MP, alive caster, out of combat).
     * Does not block when recovery would be zero — use [wouldRecoverAnyone]
     * for that warning.
     */
    fun canCast(
        skill: Skill,
        caster: Hero,
        party: Party,
        inCombat: Boolean,
    ): Boolean {
        if (inCombat || !caster.isAlive) return false
        if (!isUtility(skill)) return false
        if (skill.mpCost > caster.mp) return false
        return pickIngredient(party.inventory, skill.id) != null
    }

    /**
     * True when at least one hero would gain HP or MP from this cast
     * (always true for Make Potion, which grants inventory).
     */
    fun wouldRecoverAnyone(skill: Skill, party: Party): Boolean {
        if (skill.id == SkillCatalog.MAGE_MAKE_POTION_ID) return true
        val ingredient = pickIngredient(party.inventory, skill.id) ?: return false
        return recoveryTargets(party, skill.id, ingredient)
            .any { it.totalHp > 0 || it.totalMp > 0 }
    }

    fun resolve(
        skill: Skill,
        caster: Hero,
        party: Party,
        inCombat: Boolean,
        partyCell: Cell,
        floor: Floor?,
    ): CastPlan? {
        if (inCombat || !caster.isAlive) return null
        if (!isUtility(skill)) return null
        if (skill.mpCost > caster.mp) return null
        val ingredient = pickIngredient(party.inventory, skill.id) ?: return null
        val sequence = UtilityCastFxCatalog.frameSequence(skill.id)
        if (sequence.isEmpty()) return null
        val motion = UtilityCastFxCatalog.motionFor(skill.id)
        val isMakePotion = skill.id == SkillCatalog.MAGE_MAKE_POTION_ID
        val tickCount = UtilityCastFxCatalog.recoveryTickCount(skill.id)
        val targets = if (isMakePotion) emptyList() else recoveryTargets(party, skill.id, ingredient)
        val focusCell = if (motion == UtilityCastMotion.CAMP_SLIDE_THEN_CYCLE && floor != null) {
            UtilityCastFxCatalog.campFocusCell(floor, partyCell)
        } else {
            null
        }
        val recoveryTotals = if (targets.isEmpty()) {
            null
        } else {
            var hp = 0
            var mp = 0
            for (t in targets) {
                hp += t.totalHp
                mp += t.totalMp
            }
            RecoveryTotals(totalHp = hp, totalMp = mp)
        }
        return CastPlan(
            ingredient = ingredient,
            fxRequest = WeaponFxRequest(
                attackerCell = partyCell,
                defenderCell = partyCell,
                kind = WeaponFxKind.STAFF_SPELL_RISE,
                weaponType = caster.weapon1?.type ?: WeaponType.STAFF,
                durationMsOverride = UtilityCastFxCatalog.totalDurationMs(skill.id),
                flowFrameSequence = sequence,
                flowStepMs = UtilityCastFxCatalog.FLOW_STEP_MS,
                showStaffDuringCast = false,
                flowHeightScale = UtilityCastFxCatalog.FLOW_HEIGHT_SCALE,
                utilityMotion = motion,
                utilityFocusCell = focusCell,
                castFromPartyIcon = true,
            ),
            recovery = if (targets.isEmpty()) {
                null
            } else {
                UtilityRecoverySession.fromTargets(targets, tickCount)
            },
            potionToGrant = if (isMakePotion) {
                Potion(ingredientPotency = ingredient.potency)
            } else {
                null
            },
            recoveryTotals = recoveryTotals,
        )
    }

    fun pickIngredient(inventory: Inventory, skillId: String): Ingredient? {
        val category = categoryFor(skillId) ?: return null
        return Ingredient.inCategory(category)
            .filter { inventory.hasIngredient(it) }
            .maxByOrNull { it.potency }
    }

    /** Ingredient family consumed by an out-of-combat utility skill, if any. */
    fun ingredientCategoryFor(skillId: String): IngredientCategory? = when (skillId) {
        SkillCatalog.MAGE_MAKE_POTION_ID -> IngredientCategory.REAGENT
        SkillCatalog.FIGHTER_CAMP_ID -> IngredientCategory.CAMP
        SkillCatalog.THIEF_REST_ID -> IngredientCategory.BEVERAGE
        SkillCatalog.ARCHER_COOKING_ID -> IngredientCategory.RAW_MEAT
        else -> null
    }

    private fun categoryFor(skillId: String): IngredientCategory? = ingredientCategoryFor(skillId)

    private fun recoveryTargets(
        party: Party,
        skillId: String,
        ingredient: Ingredient,
    ): List<HeroRecoveryTarget> {
        val heroes = party.heroes
        return when (skillId) {
            SkillCatalog.FIGHTER_CAMP_ID -> heroes.mapIndexedNotNull { slot, hero ->
                if (!hero.isAlive) return@mapIndexedNotNull null
                HeroRecoveryTarget(
                    slot = slot,
                    totalHp = (hero.maxHp - hero.hp).coerceAtLeast(0),
                    totalMp = (hero.maxMp - hero.mp).coerceAtLeast(0),
                )
            }
            SkillCatalog.THIEF_REST_ID -> heroes.mapIndexedNotNull { slot, hero ->
                restRecoveryTarget(slot, hero, ingredient.potency)
            }
            SkillCatalog.ARCHER_COOKING_ID -> heroes.mapIndexedNotNull { slot, hero ->
                cookingRecoveryTarget(slot, hero, ingredient.potency)
            }
            else -> emptyList()
        }
    }

    /**
     * Rest restores HP and MP only up to a beverage-potency cap (50% /
     * 60% / 70% of max). Heroes already at or above the cap on both bars
     * are skipped.
     */
    private fun restRecoveryTarget(slot: Int, hero: Hero, potency: Int): HeroRecoveryTarget? {
        if (!hero.isAlive) return null
        val fraction = restTargetFraction(potency)
        val hpCap = resourceCap(hero.maxHp, fraction)
        val mpCap = resourceCap(hero.maxMp, fraction)
        if (hero.hp >= hpCap && hero.mp >= mpCap) return null
        val totalHp = if (hero.hp >= hpCap) 0 else hpCap - hero.hp
        val totalMp = if (hero.mp >= mpCap) 0 else mpCap - hero.mp
        if (totalHp == 0 && totalMp == 0) return null
        return HeroRecoveryTarget(slot = slot, totalHp = totalHp, totalMp = totalMp)
    }

    /** Cooking restores HP only, up to a raw-meat-potency cap (70% / 80% / 90%). */
    private fun cookingRecoveryTarget(slot: Int, hero: Hero, potency: Int): HeroRecoveryTarget? {
        if (!hero.isAlive) return null
        val hpCap = resourceCap(hero.maxHp, cookingTargetFraction(potency))
        if (hero.hp >= hpCap) return null
        val totalHp = hpCap - hero.hp
        if (totalHp <= 0) return null
        return HeroRecoveryTarget(slot = slot, totalHp = totalHp, totalMp = 0)
    }

    private fun resourceCap(max: Int, fraction: Float): Int =
        kotlin.math.ceil(max * fraction).toInt().coerceAtLeast(1)

    private fun restTargetFraction(potency: Int): Float = when (potency.coerceIn(1, 3)) {
        1 -> 0.5f
        2 -> 0.6f
        else -> 0.7f
    }

    private fun cookingTargetFraction(potency: Int): Float = when (potency.coerceIn(1, 3)) {
        1 -> 0.7f
        2 -> 0.8f
        else -> 0.9f
    }
}
