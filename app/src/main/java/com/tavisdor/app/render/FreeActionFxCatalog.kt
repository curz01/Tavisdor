package com.tavisdor.app.render

import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.skills.Skill
import com.tavisdor.app.skills.SkillCatalog

/**
 * Optional prep animations for combat free actions ([Skill.costsAction]
 * == false) that should play before the hero's main action on the same turn.
 */
object FreeActionFxCatalog {

    fun hasPrepAnimation(skillId: String): Boolean =
        CombatPartyRiseFxCatalog.isPartyRiseCast(skillId)

    fun buildFxRequest(partyCell: Cell, skillId: String): WeaponFxRequest? =
        CombatPartyRiseFxCatalog.buildFxRequest(partyCell, skillId)
}
