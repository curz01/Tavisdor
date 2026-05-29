package com.tavisdor.app.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.tavisdor.app.enemies.Enemy

/**
 * Alternates two fire sprites at the defender's feet (in front of the
 * enemy sprite). Used by Fire I / II / III impact FX.
 */
class AlternatingFireImpactFxPlayer(
    private val assets: AssetManager,
    private val spec: Spec,
) {

    data class Spec(
        val frame1Asset: String,
        val frame2Asset: String,
        val alternationCount: Int,
        val alternationMs: Long = 120L,
        val flameHeightFraction: Float = 0.58f,
        /**
         * When set, flame width = [cellPx] × this (1f = full tile width).
         * Otherwise width follows bitmap aspect × height × [flameWidthScale].
         */
        val flameWidthFraction: Float? = null,
        /** Multiplier on width after aspect-ratio fit (1.2 = 20% wider). */
        val flameWidthScale: Float = 1f,
        /** When true, anchor the flame base to the tile bottom instead of sprite feet. */
        val anchorToCellBottom: Boolean = false,
    )

    private val bitmapCache: MutableMap<String, Bitmap?> = HashMap()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var active: Active? = null
    private var onComplete: (() -> Unit)? = null

    val isActive: Boolean get() = active != null

    fun start(target: Enemy, onComplete: () -> Unit): Boolean {
        if (bitmap(spec.frame1Asset) == null || bitmap(spec.frame2Asset) == null) {
            return false
        }
        cancel()
        active = Active(target = target, elapsedMs = 0L, flashIndex = 0)
        this.onComplete = onComplete
        return true
    }

    fun cancel() {
        active = null
        onComplete = null
    }

    fun tick(deltaMs: Long): Boolean {
        val state = active ?: return false
        state.elapsedMs += deltaMs.coerceAtLeast(0L)
        val nextFlash = (state.elapsedMs / spec.alternationMs).toInt()
        if (nextFlash >= spec.alternationCount) {
            finish()
            return false
        }
        state.flashIndex = nextFlash
        return true
    }

    fun targets(enemy: Enemy): Boolean {
        val state = active ?: return false
        return state.target === enemy
    }

    fun drawInFrontOfEnemy(
        canvas: Canvas,
        enemy: Enemy,
        camCx: Float,
        camCy: Float,
        cellPx: Float,
        viewCx: Float,
        viewCy: Float,
        enemySpriteRect: RectF?,
    ) {
        val state = active ?: return
        if (state.target !== enemy) return
        val asset = if (state.flashIndex % 2 == 0) spec.frame1Asset else spec.frame2Asset
        val bmp = bitmap(asset) ?: return
        val anchor = baseAnchor(enemy, camCx, camCy, cellPx, viewCx, viewCy, enemySpriteRect)
        val h = cellPx * spec.flameHeightFraction
        val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
        val w = spec.flameWidthFraction?.let { cellPx * it }
            ?: (h * aspect * spec.flameWidthScale)
        drawSpriteBottomAnchored(canvas, bmp, anchor.first, anchor.second, w, h)
    }

    private fun baseAnchor(
        enemy: Enemy,
        camCx: Float,
        camCy: Float,
        cellPx: Float,
        viewCx: Float,
        viewCy: Float,
        enemySpriteRect: RectF?,
    ): Pair<Float, Float> {
        val sx = (enemy.cell.x - camCx) * cellPx + viewCx
        val sy = (enemy.cell.y - camCy) * cellPx + viewCy
        val centerX = if (enemySpriteRect != null) {
            (enemySpriteRect.left + enemySpriteRect.right) / 2f
        } else {
            sx + cellPx / 2f
        }
        val bottomY = when {
            spec.anchorToCellBottom -> sy + cellPx
            enemySpriteRect != null -> enemySpriteRect.bottom
            else -> sy + cellPx * CELL_BASE_Y_FRACTION
        }
        return centerX to bottomY
    }

    private fun drawSpriteBottomAnchored(
        canvas: Canvas,
        bmp: Bitmap,
        centerX: Float,
        bottomY: Float,
        width: Float,
        height: Float,
    ) {
        dstRect.set(centerX - width / 2f, bottomY - height, centerX + width / 2f, bottomY)
        srcRect.set(0, 0, bmp.width, bmp.height)
        canvas.drawBitmap(bmp, srcRect, dstRect, drawPaint)
    }

    private fun finish() {
        val cb = onComplete
        active = null
        onComplete = null
        cb?.invoke()
    }

    private fun bitmap(name: String): Bitmap? =
        bitmapCache.getOrPut(name) {
            runCatching {
                assets.open("sprites/$name.png").use { BitmapFactory.decodeStream(it) }
            }.onFailure {
                Log.w(TAG, "Failed to load sprites/$name.png: ${it.message}")
            }.getOrNull()
        }

    private data class Active(
        val target: Enemy,
        var elapsedMs: Long,
        var flashIndex: Int,
    )

    companion object {
        private const val TAG = "AlternatingFireImpactFx"
        private const val CELL_BASE_Y_FRACTION = 0.94f

        val FIRE_I = Spec("fire1", "fire2", alternationCount = 8)
        private const val DEFAULT_FLAME_HEIGHT_FRACTION = 0.58f

        val FIRE_II = Spec(
            frame1Asset = "fireii_1",
            frame2Asset = "fireii_2",
            alternationCount = 6,
            flameHeightFraction = DEFAULT_FLAME_HEIGHT_FRACTION * 1.2f,
            flameWidthFraction = 1f,
            anchorToCellBottom = true,
        )
        val FIRE_III = Spec(
            frame1Asset = "fireiii_1",
            frame2Asset = "fireiii_2",
            alternationCount = 6,
            flameHeightFraction = 1f,
            flameWidthScale = 1.2f,
            anchorToCellBottom = true,
        )
    }
}
