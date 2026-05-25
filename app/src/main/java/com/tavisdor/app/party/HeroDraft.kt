package com.tavisdor.app.party

/**
 * Transient pair of values produced by class-select and consumed by
 * [Party.create] / [com.tavisdor.app.game.Game.startNewRun]. Exists purely so
 * the call sites read as `startNewRun(drafts)` instead of
 * `startNewRun(pairs)` where it is unclear which side of the pair is which.
 */
data class HeroDraft(
    val name: String,
    val heroClass: HeroClass,
)
