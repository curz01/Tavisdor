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
 * Thief Steal on a connecting hit: [steal1] rises from the defender,
 * holds above the sprite, [steal2] layers on top, then slides up and
 * right by half a tile.
 */
class StealFxPlayer(private val assets: AssetManager) {

    private val bitmapCache: MutableMap<String, Bitmap?> = HashMap()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var active: Active? = null
    private var onComplete: (() -> Unit)? = null

    val isActive: Boolean get() = active != null

    fun start(target: Enemy, onComplete: () -> Unit): Boolean {
        if (bitmap(ASSET_STEAL_1) == null || bitmap(ASSET_STEAL_2) == null) return false
        cancel()
        active = Active(target = target, elapsedMs = 0L)
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
        if (state.elapsedMs >= TOTAL_MS) {
            finish()
            return false
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
        val bmp1 = bitmap(ASSET_STEAL_1) ?: return
        val bmp2 = bitmap(ASSET_STEAL_2) ?: return
        val center = cellCenter(enemy, camCx, camCy, cellPx, viewCx, viewCy, enemySpriteRect)
        val pose = samplePose(state.elapsedMs, center, cellPx)
        val h = cellPx * SIZE_FRACTION
        drawSpriteCentered(canvas, bmp1, pose.steal1X, pose.steal1Y, h)
        if (pose.drawSteal2) {
            drawSpriteCentered(canvas, bmp2, pose.steal2X, pose.steal2Y, h)
        }
    }

    private fun samplePose(elapsedMs: Long, center: Pair<Float, Float>, cellPx: Float): Pose {
        val peakY = center.second - cellPx * RISE_HEIGHT_FRACTION
        val peakX = center.first
        val riseEnd = RISE_MS
        val holdEnd = riseEnd + HOLD_MS

        val steal1Y = when {
            elapsedMs < riseEnd -> {
                val t = easeInOutQuad((elapsedMs.toFloat() / riseEnd).coerceIn(0f, 1f))
                center.second + (peakY - center.second) * t
            }
            else -> peakY
        }

        if (elapsedMs < holdEnd) {
            return Pose(
                steal1X = peakX,
                steal1Y = steal1Y,
                drawSteal2 = false,
                steal2X = peakX,
                steal2Y = peakY,
            )
        }

        val swipeT = easeInOutQuad(
            ((elapsedMs - holdEnd).toFloat() / SWIPE_MS.toFloat()).coerceIn(0f, 1f),
        )
        val offsetX = cellPx * SWIPE_RIGHT_TILES * swipeT
        val offsetY = -cellPx * SWIPE_UP_TILES * swipeT
        return Pose(
            steal1X = peakX,
            steal1Y = peakY,
            drawSteal2 = true,
            steal2X = peakX + offsetX,
            steal2Y = peakY + offsetY,
        )
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

    private fun easeInOutQuad(t: Float): Float =
        if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f).let { it * it } / 2f

    private data class Active(
        val target: Enemy,
        var elapsedMs: Long,
    )

    private data class Pose(
        val steal1X: Float,
        val steal1Y: Float,
        val drawSteal2: Boolean,
        val steal2X: Float,
        val steal2Y: Float,
    )

    companion object {
        private const val TAG = "StealFx"
        private const val ASSET_STEAL_1 = "steal1"
        private const val ASSET_STEAL_2 = "steal2"

        private const val RISE_MS = 500L
        private const val HOLD_MS = 350L
        private const val SWIPE_MS = 450L
        private val TOTAL_MS = RISE_MS + HOLD_MS + SWIPE_MS

        private const val RISE_HEIGHT_FRACTION = 0.38f
        private const val SIZE_FRACTION = 0.72f
        private const val SWIPE_RIGHT_TILES = 0.5f
        private const val SWIPE_UP_TILES = 0.18f
    }
}
