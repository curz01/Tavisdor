package com.tavisdor.app.party

/**
 * Transient triple of values produced by class-select and consumed by
 * [Party.create] / [com.tavisdor.app.game.Game.startNewRun]. Exists purely so
 * the call sites read as `startNewRun(drafts)` instead of
 * `startNewRun(pairs)` where it is unclear which side of the pair is which.
 *
 * [gender] is purely cosmetic - it picks the portrait sprite set
 * and the auto-name pool (see [com.tavisdor.app.party.Gender]) and
 * never affects gameplay numbers.
 */
data class HeroDraft(
    val name: String,
    val heroClass: HeroClass,
    val gender: Gender = Gender.MALE,
)
