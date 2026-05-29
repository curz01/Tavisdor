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
 * Earth III impact: [earthiii_1] behind the defender (0.5s shake),
 * then [earthiii_2] and [earthiii_3] in front (0.25s each, continued shake),
 * then [earthiii_4] in front with a heavier defender shake.
 */
class EarthIIIImpactFxPlayer(private val assets: AssetManager) {

    private val bitmapCache: MutableMap<String, Bitmap?> = HashMap()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private var active: Active? = null
    private var onComplete: (() -> Unit)? = null

    val isActive: Boolean get() = active != null

    fun start(target: Enemy, onComplete: () -> Unit): Boolean {
        val required = listOf("earthiii_1", "earthiii_2", "earthiii_3", "earthiii_4")
        if (required.any { bitmap(it) == null }) return false
        cancel()
        active = Active(
            target = target,
            phase = Phase.FRAME_1,
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
        if (state.phaseElapsedMs >= state.phase.durationMs) {
            val next = state.phase.next() ?: run {
                finish()
                return false
            }
            state.phase = next
            state.phaseElapsedMs = 0L
        }
        return true
    }

    fun targets(enemy: Enemy): Boolean {
        val state = active ?: return false
        return state.target === enemy
    }

    fun shakeOffsetPx(enemy: Enemy, cellPx: Float): Pair<Float, Float> {
        val state = active ?: return 0f to 0f
        if (state.target !== enemy) return 0f to 0f

        val heavy = state.phase == Phase.FRAME_4
        val amp = cellPx * if (heavy) SHAKE_HEAVY_AMP_FRACTION else SHAKE_AMP_FRACTION
        val freqX = if (heavy) SHAKE_HEAVY_FREQ_X else SHAKE_FREQ_X
        val freqY = if (heavy) SHAKE_HEAVY_FREQ_Y else SHAKE_FREQ_Y

        val phaseT = state.phaseElapsedMs / 1000f
        val totalT = (state.phase.ordinal * 1000L + state.phaseElapsedMs) / 1000f

        var burst = 1f
        if (!heavy) {
            val phaseProgress = (state.phaseElapsedMs.toFloat() / state.phase.durationMs).coerceIn(0f, 1f)
            if (phaseProgress < PHASE_START_BURST_FRACTION) {
                burst = 1f + 0.35f * (1f - phaseProgress / PHASE_START_BURST_FRACTION)
            }
        } else {
            val phaseProgress = (state.phaseElapsedMs.toFloat() / state.phase.durationMs).coerceIn(0f, 1f)
            burst = if (phaseProgress < HEAVY_BURST_FRACTION) {
                1.35f - 0.5f * (phaseProgress / HEAVY_BURST_FRACTION)
            } else {
                0.75f
            }
        }

        return amp * burst * sin(totalT * freqX) to amp * burst * sin(phaseT * freqY)
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
        drawPhase(canvas, enemy, camCx, camCy, cellPx, viewCx, viewCy, behind = true)
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
        drawPhase(canvas, enemy, camCx, camCy, cellPx, viewCx, viewCy, behind = false)
    }

    private fun drawPhase(
        canvas: Canvas,
        enemy: Enemy,
        camCx: Float,
        camCy: Float,
        cellPx: Float,
        viewCx: Float,
        viewCy: Float,
        behind: Boolean,
    ) {
        val state = active ?: return
        if (state.target !== enemy) return
        val phaseBehind = state.phase == Phase.FRAME_1
        if (phaseBehind != behind) return
        val bmp = bitmap(state.phase.assetName) ?: return
        val shake = shakeOffsetPx(enemy, cellPx)
        val center = cellCenter(enemy, camCx, camCy, cellPx, viewCx, viewCy)
        val sizeFrac = if (state.phase == Phase.FRAME_4) FRAME_4_SIZE_FRACTION else FRAME_SIZE_FRACTION
        drawSpriteCentered(
            canvas,
            bmp,
            center.first + shake.first,
            center.second + shake.second,
            cellPx * sizeFrac,
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

    private enum class Phase(
        val assetName: String,
        val durationMs: Long,
    ) {
        FRAME_1("earthiii_1", FRAME_1_MS),
        FRAME_2("earthiii_2", FRAME_2_3_MS),
        FRAME_3("earthiii_3", FRAME_2_3_MS),
        FRAME_4("earthiii_4", FRAME_4_MS),
        ;

        fun next(): Phase? = entries.getOrNull(ordinal + 1)
    }

    private data class Active(
        val target: Enemy,
        var phase: Phase,
        var phaseElapsedMs: Long,
    )

    companion object {
        private const val TAG = "EarthIIIImpactFx"

        private const val FRAME_1_MS = 500L
        private const val FRAME_2_3_MS = 250L
        private const val FRAME_4_MS = 400L

        private const val FRAME_SIZE_FRACTION = 1.1f
        private const val FRAME_4_SIZE_FRACTION = 1.2f

        private const val SHAKE_AMP_FRACTION = 0.048f
        private const val SHAKE_HEAVY_AMP_FRACTION = 0.092f
        private const val SHAKE_FREQ_X = 40f
        private const val SHAKE_FREQ_Y = 36f
        private const val SHAKE_HEAVY_FREQ_X = 58f
        private const val SHAKE_HEAVY_FREQ_Y = 51f
        private const val PHASE_START_BURST_FRACTION = 0.28f
        private const val HEAVY_BURST_FRACTION = 0.45f
    }
}
