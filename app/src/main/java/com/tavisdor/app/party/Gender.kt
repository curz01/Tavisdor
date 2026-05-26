package com.tavisdor.app.party

/**
 * Visual gender of a hero. Drives two things and ONLY two things:
 *   1. Which portrait sprite set is loaded in
 *      [com.tavisdor.app.render.HeroPanelRenderer] (idle cycle +
 *      hurt frame).
 *   2. Which name pool [NameGenerator] draws from when auto-
 *      assigning a placeholder name during class-select.
 *
 * Has no gameplay effect - stats, skills, AC, damage, etc. are
 * class- and level-driven. Kept as a top-level enum (rather than
 * a nested one) so the save / draft / UI layers can reference it
 * without each owning a duplicate constant.
 */
enum class Gender { MALE, FEMALE }
