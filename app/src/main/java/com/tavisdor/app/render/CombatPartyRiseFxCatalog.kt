package com.tavisdor.app.render

import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.skills.SkillCatalog

/**
 * Thief combat casts that rise from the party icon (same motion / timing
 * as Make Potion, Rest, and Cooking).
 */
object CombatPartyRiseFxCatalog {

    /** Hold at the top of the rise before the cast completes (matches intro hold). */
    val RISE_END_HOLD_MS: Long = UtilityCastFxCatalog.RISE_INTRO_HOLD_MS

    fun isPartyRiseCast(skillId: String): Boolean =
        skillId == SkillCatalog.THIEF_TRICK_ATTACK_ID ||
            skillId == SkillCatalog.THIEF_HIDE_ID

    fun frameAsset(skillId: String): String? = when (skillId) {
        SkillCatalog.THIEF_TRICK_ATTACK_ID -> "trickattack"
        SkillCatalog.THIEF_HIDE_ID -> "hide"
        else -> null
    }

    fun introDurationMs(): Long =
        UtilityCastFxCatalog.introDurationMs(UtilityCastMotion.RISE_THEN_CYCLE)

    fun totalDurationMs(): Long = introDurationMs() + RISE_END_HOLD_MS

    fun buildFxRequest(partyCell: Cell, skillId: String): WeaponFxRequest? {
        val asset = frameAsset(skillId) ?: return null
        return WeaponFxRequest(
            attackerCell = partyCell,
            defenderCell = partyCell,
            kind = WeaponFxKind.STAFF_SPELL_RISE,
            durationMsOverride = totalDurationMs(),
            flowFrameSequence = listOf(asset),
            flowStepMs = RISE_END_HOLD_MS,
            showStaffDuringCast = false,
            flowHeightScale = UtilityCastFxCatalog.FLOW_HEIGHT_SCALE,
            utilityMotion = UtilityCastMotion.RISE_THEN_CYCLE,
            castFromPartyIcon = true,
        )
    }
}
