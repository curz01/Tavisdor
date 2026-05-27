package com.tavisdor.app.combat

import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.dungeon.Floor
import com.tavisdor.app.enemies.Enemy
import com.tavisdor.app.skills.Skill
import com.tavisdor.app.skills.SkillCastType
import com.tavisdor.app.skills.SkillCatalog

/**
 * Range + target validation for the combat tile-picker overlay.
 * Uses the same Manhattan range and LOS rules as [CombatController.commitHeroAction].
 */
object CombatTargeting {

    /**
     * Tile highlight buckets for the dungeon overlay. [SPLASH] is reserved
     * for future AoE previews (different tint from [IN_RANGE]).
     */
    enum class TileHighlight {
        /** Revealed cell outside the skill's reach — drawn dimmed. */
        DIMMED,
        /** In range with clear LOS (or melee-adjacent) — normal brightness. */
        IN_RANGE,
        /** In range where a living enemy can be targeted — extra emphasis. */
        TARGETABLE_ENEMY,
        /** Reserved for future splash-radius preview. */
        SPLASH,
    }

    data class OverlayMap(
        val highlights: Map<Cell, TileHighlight>,
    )

    /**
     * True when confirming this skill should close the assign panel and
     * open the dungeon target picker (combat only).
     */
    fun requiresEnemyTargetSelection(skill: Skill): Boolean {
        if (HealResolver.isHeal(skill)) return false
        if (skill.range <= 0) return false
        if (!skill.costsAction) return false
        when (skill.id) {
            SkillCatalog.BASIC_DEFEND_ID,
            SkillCatalog.ARCHER_RAPID_FIRE_ID,
            SkillCatalog.ARCHER_DOUBLE_SHOT_ID,
            SkillCatalog.FIGHTER_CHARGE_ID,
            -> return false
        }
        if (skill.castType == SkillCastType.PREPARE &&
            skill.damage == null &&
            !skill.isSpell
        ) {
            return false
        }
        return true
    }

    fun effectiveRange(skill: Skill): Int = skill.range

    fun canTargetCell(floor: Floor, origin: Cell, skill: Skill, cell: Cell): Boolean {
        if (cell !in floor.floorCells) return false
        if (!LineOfSight.isInRange(origin, cell, effectiveRange(skill))) return false
        if (effectiveRange(skill) > 1 &&
            !LineOfSight.hasLineOfSight(floor, origin, cell)
        ) {
            return false
        }
        return true
    }

    fun livingEnemyAt(floor: Floor, cell: Cell): Enemy? {
        val enemy = floor.enemyAt(cell) ?: return null
        if (!enemy.isAlive) return null
        if (!floor.isVisibleToParty(cell)) return null
        return enemy
    }

    fun isTargetableEnemyCell(floor: Floor, origin: Cell, skill: Skill, cell: Cell): Boolean {
        if (livingEnemyAt(floor, cell) == null) return false
        return canTargetCell(floor, origin, skill, cell)
    }

    /**
     * Builds per-cell highlights for every [Floor.revealedCells] entry.
     */
    fun buildOverlayMap(floor: Floor, origin: Cell, skill: Skill): OverlayMap {
        val highlights = HashMap<Cell, TileHighlight>()
        for (cell in floor.revealedCells) {
            if (!floor.isVisibleToParty(cell)) continue
            highlights[cell] = when {
                isTargetableEnemyCell(floor, origin, skill, cell) ->
                    TileHighlight.TARGETABLE_ENEMY
                canTargetCell(floor, origin, skill, cell) ->
                    TileHighlight.IN_RANGE
                else ->
                    TileHighlight.DIMMED
            }
        }
        return OverlayMap(highlights)
    }

    fun highlightForCell(overlay: OverlayMap, cell: Cell): TileHighlight =
        overlay.highlights[cell] ?: TileHighlight.DIMMED
}
