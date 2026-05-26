package com.tavisdor.app.combat

import com.tavisdor.app.skills.Skill

/**
 * Catalogue of heal-spell amounts. Lives alongside the combat
 * controller because heals resolve through a dedicated commit
 * path ([CombatController.commitHeroHeal]) - the picker UI in
 * [com.tavisdor.app.MainActivity] needs to detect a heal BEFORE
 * firing the action so it can prompt the player to choose a
 * target hero, then resolve through the same shared amount
 * table on commit.
 *
 * Keyed by [Skill.id] (lowercase snake_case) so the table can
 * stay free of import cycles with the skill catalog and so save
 * files stay stable across renames of the player-facing
 * [Skill.displayName].
 *
 * Per the design doc:
 *   - Heal I  (level 2)  : restore 3 HP, costs 1 mana.
 *   - Heal II (level 6)  : restore 10 HP, costs 3 mana.
 *   - Heal III (level 10): restore 20 HP, costs 5 mana, +5 HP
 *                          residual next turn (residual handler
 *                          NOT YET WIRED - tracked separately).
 *
 * All three "cannot heal dead" - that rule is enforced by
 * [com.tavisdor.app.party.Hero.heal] (refuses when hp == 0)
 * AND by the picker dialog filtering KO'd heroes out of the
 * selectable list, so a dead hero never even shows up.
 */
object HealResolver {

    /**
     * Heal amount in HP keyed by skill id. Only entries present
     * here are considered heal spells by [isHeal]; everything
     * else flows through the regular damage / spell path.
     */
    private val healAmounts: Map<String, Int> = mapOf(
        "mage_heal_1" to 3,
        "mage_heal_2" to 10,
        "mage_heal_3" to 20,
    )

    /** Returns the HP to restore for [skill], or null when [skill] isn't a heal. */
    fun amountFor(skill: Skill): Int? = healAmounts[skill.id]

    /** True iff [skill] resolves through the heal commit path. */
    fun isHeal(skill: Skill): Boolean = healAmounts.containsKey(skill.id)
}
