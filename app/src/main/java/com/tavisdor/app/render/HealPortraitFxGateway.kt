package com.tavisdor.app.render

import android.graphics.Canvas
import android.graphics.RectF

/**
 * Heal spell overlay on the receiving hero's panel portrait
 * (`heali_1` / `heali_2` alternation).
 */
interface HealPortraitFxGateway {
    val isPlaying: Boolean

    fun start(targetSlot: Int, onComplete: () -> Unit): Boolean

    fun drawOnPortrait(canvas: Canvas, slot: Int, portraitRect: RectF)
}
