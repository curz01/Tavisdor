package com.tavisdor.app.render

import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.dungeon.Floor
import com.tavisdor.app.skills.SkillCatalog

/**
 * Timing and frame lists for out-of-combat utility casts.
 */
object UtilityCastFxCatalog {

    /** Per-frame hold during the cycle phase (130ms × 2 = 50% slower). */
    const val FLOW_STEP_MS: Long = 260L

    /** Utility overlay height multiplier vs spell tip FX baseline. */
    const val FLOW_HEIGHT_SCALE: Float = 1.5f

    const val CAMP_TRAVEL_MS = 650L
    const val RISE_INTRO_HOLD_MS = 450L
    const val RISE_INTRO_ASCENT_MS = 900L

    fun isUtilitySkill(skillId: String): Boolean = skillId in setOf(
        SkillCatalog.MAGE_MAKE_POTION_ID,
        SkillCatalog.FIGHTER_CAMP_ID,
        SkillCatalog.THIEF_REST_ID,
        SkillCatalog.ARCHER_COOKING_ID,
    )

    fun motionFor(skillId: String): UtilityCastMotion = when (skillId) {
        SkillCatalog.FIGHTER_CAMP_ID -> UtilityCastMotion.CAMP_SLIDE_THEN_CYCLE
        else -> UtilityCastMotion.RISE_THEN_CYCLE
    }

    fun frameSequence(skillId: String): List<String> = when (skillId) {
        SkillCatalog.MAGE_MAKE_POTION_ID -> alternate("potion_1", "potion_2", 10)
        SkillCatalog.FIGHTER_CAMP_ID -> alternate("camping_1", "camping_2", 10)
        SkillCatalog.THIEF_REST_ID -> alternate("rest1", "rest2", 10)
        SkillCatalog.ARCHER_COOKING_ID -> cookingCycle(4)
        else -> emptyList()
    }

    fun introDurationMs(motion: UtilityCastMotion): Long = when (motion) {
        UtilityCastMotion.CAMP_SLIDE_THEN_CYCLE -> CAMP_TRAVEL_MS
        UtilityCastMotion.RISE_THEN_CYCLE -> RISE_INTRO_HOLD_MS + RISE_INTRO_ASCENT_MS
    }

    fun totalDurationMs(skillId: String): Long {
        val sequence = frameSequence(skillId)
        if (sequence.isEmpty()) return 0L
        return introDurationMs(motionFor(skillId)) + sequence.size * FLOW_STEP_MS
    }

    fun recoveryTickCount(skillId: String): Int = frameSequence(skillId).size

    /**
     * Walkable floor cell beside [partyCell] for camp setup. Falls back to
     * [partyCell] when no neighbor exists.
     */
    fun campFocusCell(floor: Floor, partyCell: Cell): Cell {
        val deltas = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)
        for ((dx, dy) in deltas) {
            val c = Cell(partyCell.x + dx, partyCell.y + dy)
            if (floor.isFloor(c)) return c
        }
        return partyCell
    }

    private fun alternate(a: String, b: String, swaps: Int): List<String> =
        List(swaps) { i -> if (i % 2 == 0) a else b }

    private fun cookingPass(): List<String> =
        listOf("cooking_1", "cooking_2", "cooking_3", "cooking_2", "cooking_1")

    private fun cookingCycle(passes: Int): List<String> =
        buildList { repeat(passes) { addAll(cookingPass()) } }
}
