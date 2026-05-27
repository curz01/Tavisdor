package com.tavisdor.app.render

/**
 * Bridge between [com.tavisdor.app.combat.CombatController] and the
 * dungeon weapon-FX player owned by [com.tavisdor.app.game.Game].
 */
interface WeaponFxGateway {
    /**
     * Begins [request]. Returns true when playback started and
     * [onComplete] will run later; false when art is missing and
     * the caller should resolve damage immediately.
     */
    fun start(request: WeaponFxRequest, onComplete: () -> Unit): Boolean

    /** True while an attack animation is still playing. */
    val isPlaying: Boolean
}
