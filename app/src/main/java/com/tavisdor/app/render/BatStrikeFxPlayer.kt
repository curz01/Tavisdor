package com.tavisdor.app.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.tavisdor.app.dungeon.Cell
import com.tavisdor.app.enemies.Enemy
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Bat bite attack: rise 50% of a cell on a diagonal away from the
 * party icon, hold [BAT_FRAME_2] for 0.5s, dive on [BAT_FRAME_1]
 * through the party token (party shakes mid-dive), then return home.
 */
class BatStrikeFxPlayer(private val assets: AssetManager) {

    private enum class Phase {
        RISE,
        HOLD_BAT2,
        DIVE,
        RETURN,
    }

    private data class Active(
        val enemy: Enemy,
        val partyCell: Cell,
        val awayDx: Float,
        val awayDy: Float,
        var phase: Phase,
        var phaseElapsedMs: Long,
    )

    private val bitmapCache: MutableMap<String, Bitmap?> = HashMap()

    private var active: Active? = null
    private var onComplete: (() -> Unit)? = null

    val isActive: Boolean get() = active != null

    fun start(enemy: Enemy, partyCell: Cell, onComplete: () -> Unit): Boolean {
        if (bitmap(BAT_FRAME_1) == null || bitmap(BAT_FRAME_2) == null) return false
        cancel()
        val away = awayDiagonalFromParty(enemy.cell, partyCell)
        active = Active(
            enemy = enemy,
            partyCell = partyCell,
            awayDx = away.first,
            awayDy = away.second,
            phase = Phase.RISE,
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
            Phase.RISE -> if (state.phaseElapsedMs >= RISE_MS) {
                state.phase = Phase.HOLD_BAT2
                state.phaseElapsedMs = 0L
            }
            Phase.HOLD_BAT2 -> if (state.phaseElapsedMs >= HOLD_MS) {
                state.phase = Phase.DIVE
                state.phaseElapsedMs = 0L
            }
            Phase.DIVE -> if (state.phaseElapsedMs >= DIVE_MS) {
                state.phase = Phase.RETURN
                state.phaseElapsedMs = 0L
            }
            Phase.RETURN -> if (state.phaseElapsedMs >= RETURN_MS) {
                finish()
                return false
            }
        }
        return true
    }

    fun targets(enemy: Enemy): Boolean = active?.enemy === enemy

    fun spriteAssetOverride(enemy: Enemy): String? {
        val state = active ?: return null
        if (state.enemy !== enemy) return null
        return when (state.phase) {
            Phase.RISE, Phase.HOLD_BAT2 -> BAT_FRAME_2
            Phase.DIVE, Phase.RETURN -> BAT_FRAME_1
        }
    }

    fun enemyScreenOffsetPx(enemy: Enemy, cellPx: Float): Pair<Float, Float> {
        val state = active ?: return 0f to 0f
        if (state.enemy !== enemy) return 0f to 0f
        val awayX = state.awayDx * cellPx
        val awayY = state.awayDy * cellPx
        val holdX = awayX * AWAY_FRACTION
        val holdY = awayY * AWAY_FRACTION
        val partyDx = -state.awayDx
        val partyDy = -state.awayDy
        return when (state.phase) {
            Phase.RISE -> {
                val t = easeOutCubic((state.phaseElapsedMs.toFloat() / RISE_MS).coerceIn(0f, 1f))
                holdX * t to holdY * t
            }
            Phase.HOLD_BAT2 -> holdX to holdY
            Phase.DIVE -> {
                val t = (state.phaseElapsedMs.toFloat() / DIVE_MS).coerceIn(0f, 1f)
                val startX = holdX
                val startY = holdY
                val endX = partyDx * cellPx * DIVE_PAST_PARTY_FRACTION
                val endY = partyDy * cellPx * DIVE_PAST_PARTY_FRACTION
                lerp(startX, endX, t) to lerp(startY, endY, t)
            }
            Phase.RETURN -> {
                val t = easeInOutCubic((state.phaseElapsedMs.toFloat() / RETURN_MS).coerceIn(0f, 1f))
                val endX = partyDx * cellPx * DIVE_PAST_PARTY_FRACTION
                val endY = partyDy * cellPx * DIVE_PAST_PARTY_FRACTION
                lerp(endX, 0f, t) to lerp(endY, 0f, t)
            }
        }
    }

    fun partyShakeOffsetPx(cellPx: Float): Pair<Float, Float> {
        val state = active ?: return 0f to 0f
        if (state.phase != Phase.DIVE) return 0f to 0f
        val t = (state.phaseElapsedMs.toFloat() / DIVE_MS).coerceIn(0f, 1f)
        if (t < PARTY_SHAKE_START_T || t > PARTY_SHAKE_END_T) return 0f to 0f
        val amp = cellPx * PARTY_SHAKE_AMP_FRACTION
        val phase = state.phaseElapsedMs / 1000f
        return amp * sin(phase * PARTY_SHAKE_FREQ_X) to amp * cos(phase * PARTY_SHAKE_FREQ_Y)
    }

    private fun finish() {
        val cb = onComplete
        cancel()
        cb?.invoke()
    }

    /**
     * Diagonal unit vector pointing from the party toward [attacker]
     * (continued past the bat so the wind-up reads as up-and-away from
     * the party icon). Cardinal-only layouts snap to the diagonal in
     * that quadrant with the strongest "away" dot product.
     */
    private fun awayDiagonalFromParty(attacker: Cell, party: Cell): Pair<Float, Float> {
        val dx = (attacker.x - party.x).toFloat()
        val dy = (attacker.y - party.y).toFloat()
        if (hypot(dx.toDouble(), dy.toDouble()).toFloat() < 0.001f) {
            return diagonalUnit(-1f, -1f)
        }
        val diagonals = arrayOf(
            1f to -1f,
            1f to 1f,
            -1f to -1f,
            -1f to 1f,
        )
        var best = diagonals[0]
        var bestDot = Float.NEGATIVE_INFINITY
        for (d in diagonals) {
            val dot = d.first * dx + d.second * dy
            if (dot > bestDot) {
                bestDot = dot
                best = d
            }
        }
        return diagonalUnit(best.first, best.second)
    }

    private fun diagonalUnit(sx: Float, sy: Float): Pair<Float, Float> {
        val len = hypot(sx.toDouble(), sy.toDouble()).toFloat().coerceAtLeast(0.001f)
        return sx / len to sy / len
    }

    private fun bitmap(path: String): Bitmap? =
        bitmapCache.getOrPut(path) {
            try {
                assets.open(path).use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Missing bat strike sprite: $path", e)
                null
            }
        }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun easeOutCubic(t: Float): Float {
        val u = 1f - t
        return 1f - u * u * u
    }

    private fun easeInOutCubic(t: Float): Float =
        if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { u -> u * u * u } / 2f

    companion object {
        private const val TAG = "BatStrikeFxPlayer"
        const val BAT_FRAME_1 = "sprites/bat1.png"
        const val BAT_FRAME_2 = "sprites/bat2.png"

        /** Fraction of one cell to move away from the party before the dive. */
        private const val AWAY_FRACTION = 0.5f

        /** How far past the party center the dive travels (in cells). */
        private const val DIVE_PAST_PARTY_FRACTION = 0.35f

        private const val RISE_MS = 380L
        private const val HOLD_MS = 500L
        private const val DIVE_MS = 340L
        private const val RETURN_MS = 360L

        private const val PARTY_SHAKE_START_T = 0.38f
        private const val PARTY_SHAKE_END_T = 0.72f
        private const val PARTY_SHAKE_AMP_FRACTION = 0.045f
        private const val PARTY_SHAKE_FREQ_X = 42f
        private const val PARTY_SHAKE_FREQ_Y = 37f
    }
}
