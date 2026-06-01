package com.tavisdor.app.combat

import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.dungeon.Floor
import com.tavisdor.app.enemies.Enemy
import com.tavisdor.app.items.WeaponClassRules
import com.tavisdor.app.party.Hero
import com.tavisdor.app.skills.Skill
import com.tavisdor.app.skills.SkillCastType
import com.tavisdor.app.skills.SkillCatalog

/**
 * Range + target validation for the combat tile-picker overlay.
 * Uses the same range and LOS rules as [CombatController.commitHeroAction]
 * (Manhattan by default; Fighter + spear melee uses diagonal reach at range 1).
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
     * True when the player must pick an enemy on the dungeon map after
     * tapping Action (combat only). Covers weapon attacks, melee skills,
     * and elemental spells (Fire / Earth / etc.).
     */
    /**
     * True when [skill] needs a living enemy in range before it can
     * fire (exploration ambush or combat commit).
     */
    fun anyLivingEnemyReachable(floor: Floor, origin: Cell, skill: Skill, hero: Hero? = null): Boolean {
        val range = effectiveRange(hero, skill)
        if (range <= 0) return false
        return floor.enemies.any { enemy ->
            enemy.isAlive &&
                floor.isVisibleToParty(enemy.cell) &&
                isTargetableEnemyCell(floor, origin, skill, enemy.cell, hero)
        }
    }

    /**
     * Skills that commit against an enemy but use the combat picker
     * overlay only for the subset in [requiresEnemyTargetSelection].
     */
    fun needsEnemyTargetForCommit(skill: Skill): Boolean =
        requiresEnemyTargetSelection(skill) ||
            skill.id == SkillCatalog.FIGHTER_CHARGE_ID ||
            skill.id == SkillCatalog.FIGHTER_TAUNT_ID ||
            skill.id == SkillCatalog.ARCHER_MARK_TARGET_ID

    /**
     * Skills that show the combat tile overlay (dim + in-range highlights)
     * but commit from the Action button without picking one enemy.
     * Every [TARGETABLE_ENEMY] cell is affected (e.g. Fighter Taunt).
     */
    fun showsMultiEnemyPreview(skill: Skill): Boolean =
        skill.id == SkillCatalog.FIGHTER_TAUNT_ID

    fun requiresEnemyTargetSelection(skill: Skill): Boolean {
        if (HealResolver.isHeal(skill)) return false
        if (skill.range <= 0) return false
        if (!skill.costsAction) return false
        when (skill.id) {
            SkillCatalog.BASIC_DEFEND_ID,
            SkillCatalog.ARCHER_RAPID_FIRE_ID,
            SkillCatalog.ARCHER_DOUBLE_SHOT_ID,
            SkillCatalog.ARCHER_AIM_SHOT_ID,
            SkillCatalog.FIGHTER_CHARGE_ID,
            SkillCatalog.FIGHTER_TAUNT_ID,
            SkillCatalog.THIEF_DOUBLE_STRIKE_ID,
            SkillCatalog.THIEF_STEAL_ID,
            SkillCatalog.FIGHTER_DISARM_ID,
            SkillCatalog.FIGHTER_COUNTER_ATTACK_ID,
            -> return false
        }
        if (skill.id == SkillCatalog.THIEF_WEAK_POINT_ID) return true
        if (skill.id == SkillCatalog.ARCHER_MARK_TARGET_ID) return true
        if (skill.castType == SkillCastType.PREPARE &&
            skill.damage == null &&
            !skill.isSpell
        ) {
            return false
        }
        return true
    }

    fun effectiveRange(skill: Skill): Int = skill.range

    fun effectiveRange(hero: Hero?, skill: Skill): Int =
        if (hero != null) WeaponClassRules.effectiveSkillRange(hero, skill) else skill.range

    fun canTargetCell(
        floor: Floor,
        origin: Cell,
        skill: Skill,
        cell: Cell,
        hero: Hero? = null,
    ): Boolean {
        if (cell !in floor.floorCells) return false
        if (hero != null) {
            return WeaponClassRules.passesHeroAttackRangeAndLos(
                floor,
                origin,
                cell,
                hero,
                skill,
            )
        }
        val range = effectiveRange(hero, skill)
        if (!LineOfSight.isInRange(origin, cell, range)) return false
        if (range > 1 && !LineOfSight.hasLineOfSight(floor, origin, cell)) {
            return false
        }
        return true
    }

    /** Living, visible enemies in [skill] range with LOS (when required). */
    fun livingEnemiesInRange(
        floor: Floor,
        origin: Cell,
        skill: Skill,
        hero: Hero? = null,
    ): List<Enemy> =
        floor.enemies.filter { enemy ->
            enemy.isAlive &&
                floor.isVisibleToParty(enemy.cell) &&
                isTargetableEnemyCell(floor, origin, skill, enemy.cell, hero)
        }

    fun livingEnemyAt(floor: Floor, cell: Cell): Enemy? {
        val enemy = floor.enemyAt(cell) ?: return null
        if (!enemy.isAlive) return null
        if (!floor.isVisibleToParty(cell)) return null
        return enemy
    }

    fun isTargetableEnemyCell(
        floor: Floor,
        origin: Cell,
        skill: Skill,
        cell: Cell,
        hero: Hero? = null,
    ): Boolean {
        if (livingEnemyAt(floor, cell) == null) return false
        return canTargetCell(floor, origin, skill, cell, hero)
    }

    /**
     * Builds per-cell highlights for every walkable cell the party can
     * currently see ([Floor.isVisibleToParty]).
     */
    fun buildOverlayMap(floor: Floor, origin: Cell, skill: Skill, hero: Hero? = null): OverlayMap {
        val highlights = HashMap<Cell, TileHighlight>()
        for (cell in floor.floorCells) {
            if (!floor.isVisibleToParty(cell)) continue
            highlights[cell] = when {
                isTargetableEnemyCell(floor, origin, skill, cell, hero) ->
                    TileHighlight.TARGETABLE_ENEMY
                canTargetCell(floor, origin, skill, cell, hero) ->
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
