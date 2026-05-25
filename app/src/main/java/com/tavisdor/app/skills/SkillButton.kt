package com.tavisdor.app.skills

/**
 * Which of the 3 action buttons on the bottom hero panel a skill is
 * surfaced under. Derived from a skill's [Skill.castType] and
 * [Skill.mpCost]; see [Skill.button] for the exact rule.
 *
 *  - [ACTION] : the "ACT" button - active skills with no MP cost.
 *  - [GUARD]  : the "GRD" button - prepared or passive skills (no MP cost).
 *  - [SPELLS] : the "SPL" button - ANY skill that costs MP, regardless
 *               of cast type. "Mana cost trumps cast type."
 */
enum class SkillButton { ACTION, GUARD, SPELLS }
