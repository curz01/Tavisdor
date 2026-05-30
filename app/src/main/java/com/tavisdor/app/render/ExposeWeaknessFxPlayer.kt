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
 * Expose Weakness on a defender: cycles [expose_1] … [expose_7], then
 * holds [expose_8] for 0.5s, centered on the enemy sprite.
 */
class ExposeWeaknessFxPlayer(private val assets: AssetManager) {

    private val bitmapCache: MutableMap<String, Bitmap?> = HashMap()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var active: Active? = null
    private var onComplete: (() -> Unit)? = null

    val isActive: Boolean get() = active != null

    fun start(target: Enemy, onComplete: () -> Unit): Boolean {
        if (bitmap(frameAsset(1)) == null) return false
        cancel()
        active = Active(
            target = target,
            frameIndex = 0,
            phaseElapsedMs = 0L,
        )
        this.onComplete = onComplete
        return true
    }

    fun cancel() {
        active = null
        onComplete = null
    }

    fun tick(deltaMs: Long): Boolean {
        val state = active ?: return false
        state.phaseElapsedMs += deltaMs.coerceAtLeast(0L)
        val duration = frameDurationMs(state.frameIndex)
        if (state.phaseElapsedMs >= duration) {
            if (state.frameIndex >= FRAME_COUNT - 1) {
                finish()
                return false
            }
            state.frameIndex++
            state.phaseElapsedMs = 0L
        }
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
        val bmp = bitmap(frameAsset(state.frameIndex + 1))
            ?: bitmap(frameAsset(1))
            ?: return
        val center = cellCenter(enemy, camCx, camCy, cellPx, viewCx, viewCy, enemySpriteRect)
        drawSpriteCentered(canvas, bmp, center.first, center.second, cellPx * SIZE_FRACTION)
    }

    private fun drawSpriteCentered(
        canvas: Canvas,
        bmp: Bitmap,
        centerX: Float,
        centerY: Float,
        targetHeight: Float,
    ) {
        val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
        val h = targetHeight
        val w = h * aspect
        dstRect.set(centerX - w / 2f, centerY - h / 2f, centerX + w / 2f, centerY + h / 2f)
        srcRect.set(0, 0, bmp.width, bmp.height)
        canvas.drawBitmap(bmp, srcRect, dstRect, drawPaint)
    }

    private fun cellCenter(
        enemy: Enemy,
        camCx: Float,
        camCy: Float,
        cellPx: Float,
        viewCx: Float,
        viewCy: Float,
        enemySpriteRect: RectF?,
    ): Pair<Float, Float> {
        if (enemySpriteRect != null) {
            return enemySpriteRect.centerX() to enemySpriteRect.centerY()
        }
        val sx = (enemy.cell.x - camCx) * cellPx + viewCx
        val sy = (enemy.cell.y - camCy) * cellPx + viewCy
        return sx + cellPx / 2f to sy + cellPx / 2f
    }

    private fun frameDurationMs(frameIndex: Int): Long =
        if (frameIndex == FRAME_COUNT - 1) HOLD_MS else CYCLE_FRAME_MS

    private fun frameAsset(index: Int): String = "expose_$index"

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
        var frameIndex: Int,
        var phaseElapsedMs: Long,
    )

    companion object {
        private const val TAG = "ExposeWeaknessFx"
        private const val FRAME_COUNT = 8

        private const val CYCLE_FRAME_MS = 120L
        private const val HOLD_MS = 500L

        private const val SIZE_FRACTION = 0.9f
    }
}
