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
 * Earth I impact on a defender: rumble [earthi_1]/[earthi_2] behind the
 * sprite with shake, then [earthi_3] behind with a swallowing pulse.
 */
class EarthIImpactFxPlayer(private val assets: AssetManager) {

    private val bitmapCache: MutableMap<String, Bitmap?> = HashMap()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var active: Active? = null
    private var onComplete: (() -> Unit)? = null

    val isActive: Boolean get() = active != null

    fun start(target: Enemy, onComplete: () -> Unit): Boolean {
        if (bitmap("earthi_1") == null || bitmap("earthi_2") == null || bitmap("earthi_3") == null) {
            return false
        }
        cancel()
        active = Active(target = target, phase = Phase.RUMBLE, phaseElapsedMs = 0L, flashIndex = 0)
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
            Phase.RUMBLE -> {
                val nextFlash = (state.phaseElapsedMs / ALTERNATION_MS).toInt()
                if (nextFlash >= ALTERNATION_COUNT) {
                    state.phase = Phase.HOLE
                    state.phaseElapsedMs = 0L
                } else {
                    state.flashIndex = nextFlash
                }
            }
            Phase.HOLE -> {
                if (state.phaseElapsedMs >= HOLE_HOLD_MS) {
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
        if (state.target !== enemy || state.phase != Phase.RUMBLE) return 0f to 0f
        val amp = cellPx * SHAKE_AMP_FRACTION
        val t = state.phaseElapsedMs / 1000f
        return amp * sin(t * SHAKE_FREQ_X) to amp * cos(t * SHAKE_FREQ_Y)
    }

    fun drawBehindEnemy(
        canvas: Canvas,
        enemy: Enemy,
        camCx: Float,
        camCy: Float,
        cellPx: Float,
        viewCx: Float,
        viewCy: Float,
    ) {
        val state = active ?: return
        if (state.target !== enemy) return
        val shake = shakeOffsetPx(enemy, cellPx)
        val center = cellCenter(enemy, camCx, camCy, cellPx, viewCx, viewCy)
        val drawX = center.first + shake.first
        val drawY = center.second + shake.second
        when (state.phase) {
            Phase.RUMBLE -> {
                val asset = if (state.flashIndex % 2 == 0) "earthi_1" else "earthi_2"
                val bmp = bitmap(asset) ?: return
                drawSpriteCentered(canvas, bmp, drawX, drawY, cellPx * RUMBLE_SIZE_FRACTION)
            }
            Phase.HOLE -> {
                val bmp = bitmap("earthi_3") ?: return
                val pulseT = (state.phaseElapsedMs.toFloat() / HOLE_HOLD_MS).coerceIn(0f, 1f)
                val pulse = 1f + HOLE_PULSE_AMP * sin(pulseT * Math.PI.toFloat() * HOLE_PULSE_CYCLES * 2f)
                drawSpriteCentered(canvas, bmp, drawX, drawY, cellPx * HOLE_SIZE_FRACTION * pulse)
            }
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
        enemy: Enemy,
        camCx: Float,
        camCy: Float,
        cellPx: Float,
        viewCx: Float,
        viewCy: Float,
    ): Pair<Float, Float> {
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

    private enum class Phase { RUMBLE, HOLE }

    private data class Active(
        val target: Enemy,
        var phase: Phase,
        var phaseElapsedMs: Long,
        var flashIndex: Int,
    )

    companion object {
        private const val TAG = "EarthIImpactFx"

        /** Six swaps between earthi_1 and earthi_2. */
        private const val ALTERNATION_COUNT = 6
        private const val ALTERNATION_MS = 130L

        private const val HOLE_HOLD_MS = 1000L
        private const val HOLE_PULSE_AMP = 0.14f
        private const val HOLE_PULSE_CYCLES = 2.5f

        private const val RUMBLE_SIZE_FRACTION = 1.05f
        private const val HOLE_SIZE_FRACTION = 1.22f

        private const val SHAKE_AMP_FRACTION = 0.045f
        private const val SHAKE_FREQ_X = 38f
        private const val SHAKE_FREQ_Y = 33f
    }
}
