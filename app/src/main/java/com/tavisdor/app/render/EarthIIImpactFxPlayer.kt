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
import kotlin.math.sin

/**
 * Earth II impact: [earthi_1]/[earthi_2] ×4 behind the defender, then
 * [earthii_1]/[earthii_2] ×6 in front with a shake hit on each swap.
 */
class EarthIIImpactFxPlayer(private val assets: AssetManager) {

    private val bitmapCache: MutableMap<String, Bitmap?> = HashMap()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var active: Active? = null
    private var onComplete: (() -> Unit)? = null

    val isActive: Boolean get() = active != null

    fun start(target: Enemy, onComplete: () -> Unit): Boolean {
        val required = listOf("earthi_1", "earthi_2", "earthii_1", "earthii_2")
        if (required.any { bitmap(it) == null }) return false
        cancel()
        active = Active(
            target = target,
            phase = Phase.EARTH_I_BEHIND,
            phaseElapsedMs = 0L,
            flashIndex = 0,
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
            Phase.EARTH_I_BEHIND -> {
                val nextFlash = (state.phaseElapsedMs / ALTERNATION_MS).toInt()
                if (nextFlash >= EARTH_I_ALTERNATION_COUNT) {
                    state.phase = Phase.EARTH_II_FRONT
                    state.phaseElapsedMs = 0L
                    state.flashIndex = 0
                } else {
                    state.flashIndex = nextFlash
                }
            }
            Phase.EARTH_II_FRONT -> {
                val nextFlash = (state.phaseElapsedMs / ALTERNATION_MS).toInt()
                if (nextFlash >= EARTH_II_ALTERNATION_COUNT) {
                    finish()
                    return false
                }
                state.flashIndex = nextFlash
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
        if (state.target !== enemy || state.phase != Phase.EARTH_II_FRONT) return 0f to 0f

        val amp = cellPx * SHAKE_II_AMP_FRACTION
        val flashMs = (state.phaseElapsedMs % ALTERNATION_MS).toFloat()
        val flashT = (flashMs / ALTERNATION_MS).coerceIn(0f, 1f)
        val burst = if (flashT < SHAKE_BURST_FRACTION) {
            1f - flashT / SHAKE_BURST_FRACTION
        } else {
            0.18f
        }
        val t = state.phaseElapsedMs / 1000f
        return amp * burst * sin(t * SHAKE_FREQ_X) to amp * burst * sin(t * SHAKE_FREQ_Y)
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
        if (state.target !== enemy || state.phase != Phase.EARTH_I_BEHIND) return
        val asset = if (state.flashIndex % 2 == 0) "earthi_1" else "earthi_2"
        val bmp = bitmap(asset) ?: return
        val center = cellCenter(enemy, camCx, camCy, cellPx, viewCx, viewCy)
        drawSpriteCentered(canvas, bmp, center.first, center.second, cellPx * EARTH_I_SIZE_FRACTION)
    }

    fun drawInFrontOfEnemy(
        canvas: Canvas,
        enemy: Enemy,
        camCx: Float,
        camCy: Float,
        cellPx: Float,
        viewCx: Float,
        viewCy: Float,
    ) {
        val state = active ?: return
        if (state.target !== enemy || state.phase != Phase.EARTH_II_FRONT) return
        val asset = if (state.flashIndex % 2 == 0) "earthii_1" else "earthii_2"
        val bmp = bitmap(asset) ?: return
        val shake = shakeOffsetPx(enemy, cellPx)
        val center = cellCenter(enemy, camCx, camCy, cellPx, viewCx, viewCy)
        drawSpriteCentered(
            canvas,
            bmp,
            center.first + shake.first,
            center.second + shake.second,
            cellPx * EARTH_II_SIZE_FRACTION,
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

    private enum class Phase {
        EARTH_I_BEHIND,
        EARTH_II_FRONT,
    }

    private data class Active(
        val target: Enemy,
        var phase: Phase,
        var phaseElapsedMs: Long,
        var flashIndex: Int,
    )

    companion object {
        private const val TAG = "EarthIIImpactFx"

        private const val EARTH_I_ALTERNATION_COUNT = 4
        private const val EARTH_II_ALTERNATION_COUNT = 6
        private const val ALTERNATION_MS = 130L

        private const val EARTH_I_SIZE_FRACTION = 1.05f
        private const val EARTH_II_SIZE_FRACTION = 1.08f

        private const val SHAKE_II_AMP_FRACTION = 0.065f
        private const val SHAKE_BURST_FRACTION = 0.42f
        private const val SHAKE_FREQ_X = 52f
        private const val SHAKE_FREQ_Y = 47f
    }
}
