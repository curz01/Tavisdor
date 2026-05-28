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
 * Fire I impact on a defender: alternates [fire1] / [fire2] at the feet
 * (in front of the enemy sprite).
 */
class FireIImpactFxPlayer(private val assets: AssetManager) {

    private val bitmapCache: MutableMap<String, Bitmap?> = HashMap()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var active: Active? = null
    private var onComplete: (() -> Unit)? = null

    val isActive: Boolean get() = active != null

    fun start(target: Enemy, onComplete: () -> Unit): Boolean {
        if (bitmap("fire1") == null || bitmap("fire2") == null) {
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
        val nextFlash = (state.elapsedMs / ALTERNATION_MS).toInt()
        if (nextFlash >= ALTERNATION_COUNT) {
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
        val asset = if (state.flashIndex % 2 == 0) "fire1" else "fire2"
        val bmp = bitmap(asset) ?: return
        val anchor = baseAnchor(enemy, camCx, camCy, cellPx, viewCx, viewCy, enemySpriteRect)
        drawSpriteBottomAnchored(canvas, bmp, anchor.first, anchor.second, cellPx * FLAME_HEIGHT_FRACTION)
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
        if (enemySpriteRect != null) {
            return (enemySpriteRect.left + enemySpriteRect.right) / 2f to enemySpriteRect.bottom
        }
        val sx = (enemy.cell.x - camCx) * cellPx + viewCx
        val sy = (enemy.cell.y - camCy) * cellPx + viewCy
        return sx + cellPx / 2f to sy + cellPx * CELL_BASE_Y_FRACTION
    }

    private fun drawSpriteBottomAnchored(
        canvas: Canvas,
        bmp: Bitmap,
        centerX: Float,
        bottomY: Float,
        targetHeight: Float,
    ) {
        val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
        val h = targetHeight
        val w = h * aspect
        dstRect.set(centerX - w / 2f, bottomY - h, centerX + w / 2f, bottomY)
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
        private const val TAG = "FireIImpactFx"

        private const val ALTERNATION_COUNT = 8
        private const val ALTERNATION_MS = 120L

        private const val FLAME_HEIGHT_FRACTION = 0.58f
        private const val CELL_BASE_Y_FRACTION = 0.94f
    }
}
