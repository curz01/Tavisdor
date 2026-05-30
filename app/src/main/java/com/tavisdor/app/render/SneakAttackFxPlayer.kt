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
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sneak Attack impact on a defender: [sneakattack.png] rotates 100°
 * counter-clockwise around its lower-right corner, holds for 0.5s, then
 * the defender sprite shakes briefly from the hit.
 */
class SneakAttackFxPlayer(private val assets: AssetManager) {

    private val bitmapCache: MutableMap<String, Bitmap?> = HashMap()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var active: Active? = null
    private var onComplete: (() -> Unit)? = null

    val isActive: Boolean get() = active != null

    fun start(target: Enemy, onComplete: () -> Unit): Boolean {
        if (bitmap(ASSET) == null) return false
        cancel()
        active = Active(
            target = target,
            phase = Phase.ROTATE,
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
        when (state.phase) {
            Phase.ROTATE -> {
                if (state.phaseElapsedMs >= ROTATE_MS) {
                    state.phase = Phase.HOLD
                    state.phaseElapsedMs = 0L
                }
            }
            Phase.HOLD -> {
                if (state.phaseElapsedMs >= HOLD_MS) {
                    state.phase = Phase.IMPACT
                    state.phaseElapsedMs = 0L
                }
            }
            Phase.IMPACT -> {
                if (state.phaseElapsedMs >= IMPACT_SHAKE_MS) {
                    finish()
                    return false
                }
            }
        }
        return true
    }

    fun targets(enemy: Enemy): Boolean {
        val state = active ?: return false
        return state.target === enemy
    }

    fun shakeOffsetPx(enemy: Enemy, cellPx: Float): Pair<Float, Float> {
        val state = active ?: return 0f to 0f
        if (state.target !== enemy || state.phase != Phase.IMPACT) return 0f to 0f
        val amp = cellPx * SHAKE_AMP_FRACTION
        val t = state.phaseElapsedMs / 1000f
        return amp * sin(t * SHAKE_FREQ_X) to amp * cos(t * SHAKE_FREQ_Y)
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
        val bmp = bitmap(ASSET) ?: return
        val shake = shakeOffsetPx(enemy, cellPx)
        val center = cellCenter(enemy, camCx, camCy, cellPx, viewCx, viewCy, enemySpriteRect)
        val rotationDeg = currentRotationDeg(state)
        drawRotatedStrike(
            canvas = canvas,
            bmp = bmp,
            centerX = center.first + shake.first,
            centerY = center.second + shake.second - cellPx * VERTICAL_SHIFT_UP_TILES,
            cellPx = cellPx,
            rotationDeg = rotationDeg,
        )
    }

    private fun currentRotationDeg(state: Active): Float {
        return when (state.phase) {
            Phase.ROTATE -> {
                val t = (state.phaseElapsedMs.toFloat() / ROTATE_MS).coerceIn(0f, 1f)
                TARGET_ROTATION_DEG * easeOutCubic(t)
            }
            Phase.HOLD, Phase.IMPACT -> TARGET_ROTATION_DEG
        }
    }

    /**
     * Draw [bmp] centered on ([centerX], [centerY]), rotated around the
     * bitmap's lower-right corner (counter-clockwise [rotationDeg]).
     */
    private fun drawRotatedStrike(
        canvas: Canvas,
        bmp: Bitmap,
        centerX: Float,
        centerY: Float,
        cellPx: Float,
        rotationDeg: Float,
    ) {
        val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
        val h = cellPx * STRIKE_HEIGHT_FRACTION
        val w = h * aspect
        val left = centerX - w / 2f
        val top = centerY - h / 2f
        val right = left + w
        val bottom = top + h

        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRect.set(left, top, right, bottom)

        val pivotX = right
        val pivotY = bottom

        canvas.save()
        canvas.translate(pivotX, pivotY)
        canvas.rotate(rotationDeg)
        canvas.translate(-pivotX, -pivotY)
        canvas.drawBitmap(bmp, srcRect, dstRect, drawPaint)
        canvas.restore()
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
        val sx = (enemy.cell.x - camCx) * cellPx + viewCx + cellPx / 2f
        val sy = (enemy.cell.y - camCy) * cellPx + viewCy + cellPx / 2f
        return sx to sy
    }

    private fun easeOutCubic(t: Float): Float {
        val inv = 1f - t
        return 1f - inv * inv * inv
    }

    private fun finish() {
        val cb = onComplete
        active = null
        onComplete = null
        cb?.invoke()
    }

    private fun bitmap(name: String): Bitmap? {
        bitmapCache[name]?.let { return it }
        val loaded = try {
            assets.open("sprites/$name.png").use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
        if (loaded == null) {
            Log.w(TAG, "Missing asset: sprites/$name.png")
        }
        bitmapCache[name] = loaded
        return loaded
    }

    private data class Active(
        val target: Enemy,
        var phase: Phase,
        var phaseElapsedMs: Long,
    )

    private enum class Phase {
        ROTATE,
        HOLD,
        IMPACT,
    }

    companion object {
        private const val TAG = "SneakAttackFx"
        private const val ASSET = "sneakattack"

        /** Counter-clockwise degrees (Android canvas: negative = CCW). */
        private const val TARGET_ROTATION_DEG = -100f

        private const val ROTATE_MS = 320L
        private const val HOLD_MS = 500L
        private const val IMPACT_SHAKE_MS = 480L

        private const val STRIKE_HEIGHT_FRACTION = 0.85f

        /** Screen-space shift: one tile up from defender anchor. */
        private const val VERTICAL_SHIFT_UP_TILES = 1f

        private const val SHAKE_AMP_FRACTION = 0.028f
        private const val SHAKE_FREQ_X = 38f
        private const val SHAKE_FREQ_Y = 31f
    }
}
