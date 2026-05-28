package com.tavisdor.app.render

import android.graphics.RectF
import com.tavisdor.app.enemies.Enemy

/**
 * Host-owned spell visuals that play on a defender cell (layered over /
 * under the enemy sprite). Chained after weapon / staff FX in combat.
 */
interface DefenderSpellFxGateway {
    fun startEarthI(target: Enemy, onComplete: () -> Unit): Boolean

    fun startFireI(target: Enemy, onComplete: () -> Unit): Boolean

    val isPlaying: Boolean

    fun targets(enemy: Enemy): Boolean

    /** Pixel offset applied to the enemy sprite and rumble overlays. */
    fun shakeOffsetPx(enemy: Enemy, cellPx: Float): Pair<Float, Float>

    fun drawBehindEnemy(
        canvas: android.graphics.Canvas,
        enemy: Enemy,
        camCx: Float,
        camCy: Float,
        cellPx: Float,
        viewCx: Float,
        viewCy: Float,
    )

    fun drawInFrontOfEnemy(
        canvas: android.graphics.Canvas,
        enemy: Enemy,
        camCx: Float,
        camCy: Float,
        cellPx: Float,
        viewCx: Float,
        viewCy: Float,
        enemySpriteRect: RectF? = null,
    )
}
