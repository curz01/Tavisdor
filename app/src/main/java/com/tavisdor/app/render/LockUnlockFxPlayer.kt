package com.tavisdor.app.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.tavisdor.app.dungeon.Cell
import kotlin.math.cos
import kotlin.math.sin

/**
 * Door / chest lock attempt: [lock.png] rises from the tile, pauses with a
 * small shake, then on success swaps to [unlock.png] for 0.5s before lowering
 * back to the tile while fading out.
 */
class LockUnlockFxPlayer(private val assets: AssetManager) {

    private val bitmapCache: MutableMap<String, Bitmap?> = HashMap()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var active: Active? = null
    private var onComplete: (() -> Unit)? = null

    val isActive: Boolean get() = active != null

    fun start(cell: Cell, success: Boolean, onComplete: () -> Unit): Boolean {
        if (bitmap(ASSET_LOCK) == null) return false
        if (success && bitmap(ASSET_UNLOCK) == null) return false
        cancel()
        active = Active(cell = cell, success = success, elapsedMs = 0L)
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
        if (state.elapsedMs >= totalDurationMs(state.success)) {
            finish()
            return false
        }
        return true
    }

    fun draw(
        canvas: Canvas,
        camCx: Float,
        camCy: Float,
        cellPx: Float,
        viewCx: Float,
        viewCy: Float,
    ) {
        val state = active ?: return
        val center = cellCenter(state.cell, camCx, camCy, cellPx, viewCx, viewCy)
        val sample = bitmap(ASSET_LOCK) ?: return
        val (drawX, drawY, asset, alpha) = samplePose(state, center, cellPx)
        val bmp = bitmap(asset) ?: return
        drawPaint.alpha = alpha
        drawSpriteCentered(canvas, bmp, drawX, drawY, cellPx * SIZE_FRACTION)
        drawPaint.alpha = 255
    }

    private fun samplePose(
        state: Active,
        center: Pair<Float, Float>,
        cellPx: Float,
    ): Pose {
        val elapsed = state.elapsedMs
        val success = state.success
        val baseY = center.second
        val peakY = baseY - cellPx * RISE_HEIGHT_FRACTION
        val riseEnd = RISE_HOLD_MS + RISE_ASCENT_MS
        val shakeEnd = riseEnd + SHAKE_MS
        val unlockEnd = shakeEnd + if (success) UNLOCK_HOLD_MS else 0L
        val total = unlockEnd + DESCEND_MS

        return when {
            elapsed < RISE_HOLD_MS -> {
                Pose(center.first, baseY, ASSET_LOCK, 255)
            }
            elapsed < riseEnd -> {
                val t = easeInOutQuad(
                    ((elapsed - RISE_HOLD_MS).toFloat() / RISE_ASCENT_MS.toFloat())
                        .coerceIn(0f, 1f),
                )
                Pose(center.first, baseY + (peakY - baseY) * t, ASSET_LOCK, 255)
            }
            elapsed < shakeEnd -> {
                val shakeElapsed = elapsed - riseEnd
                val (ox, oy) = shakeOffset(shakeElapsed, cellPx)
                Pose(center.first + ox, peakY + oy, ASSET_LOCK, 255)
            }
            elapsed < unlockEnd -> {
                Pose(center.first, peakY, ASSET_UNLOCK, 255)
            }
            elapsed < total -> {
                val descendElapsed = elapsed - unlockEnd
                val t = easeInOutQuad(
                    (descendElapsed.toFloat() / DESCEND_MS.toFloat()).coerceIn(0f, 1f),
                )
                val asset = if (success) ASSET_UNLOCK else ASSET_LOCK
                val alpha = (255f * (1f - t)).toInt().coerceIn(0, 255)
                Pose(center.first, peakY + (baseY - peakY) * t, asset, alpha)
            }
            else -> Pose(center.first, baseY, ASSET_LOCK, 0)
        }
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
        cell: Cell,
        camCx: Float,
        camCy: Float,
        cellPx: Float,
        viewCx: Float,
        viewCy: Float,
    ): Pair<Float, Float> {
        val sx = (cell.x - camCx) * cellPx + viewCx
        val sy = (cell.y - camCy) * cellPx + viewCy
        return sx + cellPx / 2f to sy + cellPx / 2f
    }

    private fun shakeOffset(elapsedMs: Long, cellPx: Float): Pair<Float, Float> {
        val t = elapsedMs / 1000f
        val amp = cellPx * SHAKE_AMP_FRACTION
        return amp * sin(t * SHAKE_FREQ_X) to amp * cos(t * SHAKE_FREQ_Y)
    }

    private fun totalDurationMs(success: Boolean): Long =
        RISE_HOLD_MS + RISE_ASCENT_MS + SHAKE_MS +
            (if (success) UNLOCK_HOLD_MS else 0L) + DESCEND_MS

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
        val cell: Cell,
        val success: Boolean,
        var elapsedMs: Long,
    )

    private data class Pose(
        val x: Float,
        val y: Float,
        val asset: String,
        val alpha: Int,
    )

    private fun easeInOutQuad(t: Float): Float =
        if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f).let { it * it } / 2f

    companion object {
        private const val TAG = "LockUnlockFx"
        private const val ASSET_LOCK = "lock"
        private const val ASSET_UNLOCK = "unlock"

        private const val RISE_HOLD_MS = 200L
        private const val RISE_ASCENT_MS = 700L
        private const val SHAKE_MS = 400L
        private const val UNLOCK_HOLD_MS = 500L
        private const val DESCEND_MS = 600L

        private const val RISE_HEIGHT_FRACTION = 0.85f
        private const val SIZE_FRACTION = 0.72f
        private const val SHAKE_AMP_FRACTION = 0.03f
        private const val SHAKE_FREQ_X = 38f
        private const val SHAKE_FREQ_Y = 31f
    }
}
