package com.tavisdor.app.skills

/**
 * Which of the 2 action buttons on the bottom hero panel a skill is
 * surfaced under. Derived from a skill's [Skill.castType] (with an
 * optional per-skill override via [Skill.buttonOverride]); see
 * [Skill.button] for the resolved value.
 *
 *  - [ACTION] : the "ACT" button - damage skills and spells. Anything
 *               the hero swings / casts on the current turn. Mana
 *               cost no longer steers bucketing on its own; spells
 *               just go here because they're ACTIVE casts.
 *  - [GUARD]  : the "GRD" button - prepared / passive skills + the
 *               universal Defend, anything defensive or setup that
 *               isn't a direct strike.
 *
 * Note for skill authors: the SPELLS bucket was retired in favor of
 * collapsing spells into ACTION. If you need a skill that doesn't
 * cleanly match the cast-type derivation (e.g. an ACTIVE Defend),
 * set [Skill.buttonOverride] explicitly rather than warping the
 * cast type.
 */
enum class SkillButton { ACTION, GUARD }
