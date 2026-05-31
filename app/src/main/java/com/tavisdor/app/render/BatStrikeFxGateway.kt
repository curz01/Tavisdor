package com.tavisdor.app.render

import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.enemies.Enemy

/**
 * Bridge between [com.tavisdor.app.combat.CombatController] and the
 * bat dive-bite animation owned by [com.tavisdor.app.game.Game].
 */
interface BatStrikeFxGateway {
    fun start(enemy: Enemy, partyCell: Cell, onComplete: () -> Unit): Boolean

    val isPlaying: Boolean

    fun targets(enemy: Enemy): Boolean

    /** Overrides walk-cycle art while the dive FX is active. */
    fun spriteAssetOverride(enemy: Enemy): String?

    /** Extra screen-space offset applied when drawing the bat sprite. */
    fun enemyScreenOffsetPx(enemy: Enemy, cellPx: Float): Pair<Float, Float>

    /** Subtle party-token shake during the dive impact window. */
    fun partyShakeOffsetPx(cellPx: Float): Pair<Float, Float>
}
