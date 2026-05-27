package com.tavisdor.app.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import com.tavisdor.app.dungeon.Cell
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Plays one weapon / spell attack animation between grid cells.
 * Sprites are authored point-up; each frame rotates them toward
 * the defender before drawing.
 */
class WeaponAttackFxPlayer(private val assets: AssetManager) {

    private val bitmapCache: MutableMap<String, Bitmap?> = HashMap()
    private val srcRect = Rect()
    private val dstRect = RectF()
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val staffGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(90, 255, 220, 120)
        style = Paint.Style.FILL
    }

    private var activeRequest: WeaponFxRequest? = null
    private var elapsedMs: Long = 0L
    private var onComplete: (() -> Unit)? = null

    val isActive: Boolean get() = activeRequest != null

    fun start(request: WeaponFxRequest, onComplete: () -> Unit): Boolean {
        if (!canPlay(request)) return false
        cancel()
        activeRequest = request
        this.onComplete = onComplete
        elapsedMs = 0L
        return true
    }

    fun cancel() {
        activeRequest = null
        onComplete = null
        elapsedMs = 0L
    }

    /**
     * Advances playback. Returns true while the animation is still
     * running (host should keep invalidating).
     */
    fun tick(deltaMs: Long): Boolean {
        val request = activeRequest ?: return false
        elapsedMs += deltaMs.coerceAtLeast(0L)
        if (elapsedMs >= durationMs(request)) {
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
        density: Float,
        attackerScreenOverride: Pair<Float, Float>? = null,
    ) {
        val request = activeRequest ?: return
        val attacker = if (request.kind == WeaponFxKind.CHARGE_SWORD_HOLD && attackerScreenOverride != null) {
            attackerScreenOverride
        } else {
            cellCenter(request.attackerCell, camCx, camCy, cellPx, viewCx, viewCy)
        }
        val defender = cellCenter(request.defenderCell, camCx, camCy, cellPx, viewCx, viewCy)
        val aimDeg = aimDegrees(attacker.first, attacker.second, defender.first, defender.second)
        val scale = cellPx * SPRITE_HEIGHT_FRACTION

        when (request.kind) {
            WeaponFxKind.MELEE_ARC, WeaponFxKind.STAFF_MELEE_ARC -> {
                val asset = if (request.kind == WeaponFxKind.STAFF_MELEE_ARC) {
                    "staff"
                } else {
                    WeaponFxCatalog.meleeArcAsset(request.weaponType)
                }
                drawMeleeArc(canvas, asset, attacker, aimDeg, scale, elapsedMs, MELEE_ARC_MS)
            }
            WeaponFxKind.SPEAR_THRUST -> {
                drawThrust(canvas, "spear", attacker, defender, aimDeg, scale, elapsedMs, SPEAR_MS)
            }
            WeaponFxKind.STAFF_SPELL_RISE -> {
                drawStaffSpell(canvas, attacker, aimDeg, scale, elapsedMs, STAFF_SPELL_MS, density)
            }
            WeaponFxKind.DAGGER_COMBO -> {
                drawDaggerCombo(canvas, attacker, defender, aimDeg, scale, elapsedMs)
            }
            WeaponFxKind.BOW_SHOT, WeaponFxKind.FIRE_PROJECTILE -> {
                val arrowAsset = if (request.kind == WeaponFxKind.FIRE_PROJECTILE) {
                    "fire_arrow"
                } else {
                    "arrow"
                }
                val plan = request.bowVolleyPlan
                if (plan != null) {
                    drawBowVolleys(
                        canvas = canvas,
                        attacker = attacker,
                        defender = defender,
                        aimDeg = aimDeg,
                        scale = scale,
                        cellPx = cellPx,
                        elapsed = elapsedMs,
                        plan = plan,
                    )
                } else {
                    drawBowShot(
                        canvas = canvas,
                        attacker = attacker,
                        defender = defender,
                        aimDeg = aimDeg,
                        scale = scale,
                        cellPx = cellPx,
                        elapsed = elapsedMs,
                        arrowAsset = arrowAsset,
                    )
                }
            }
            WeaponFxKind.CHARGE_SWORD_HOLD -> {
                bitmap("sword")?.let {
                    drawWeaponAtPivot(
                        canvas = canvas,
                        bmp = it,
                        pivotX = attacker.first,
                        pivotY = attacker.second,
                        aimDeg = aimDeg,
                        targetHeight = scale,
                    )
                }
            }
        }
    }

    // ---- Playback helpers ----

    private fun finish() {
        val cb = onComplete
        activeRequest = null
        onComplete = null
        elapsedMs = 0L
        cb?.invoke()
    }

    private fun canPlay(request: WeaponFxRequest): Boolean = when (request.kind) {
        WeaponFxKind.DAGGER_COMBO ->
            bitmap("dagger_r") != null && bitmap("dagger_l") != null
        WeaponFxKind.BOW_SHOT -> bowAssetsReady("arrow", request.bowVolleyPlan)
        WeaponFxKind.FIRE_PROJECTILE -> bowAssetsReady("fire_arrow", request.bowVolleyPlan)
        WeaponFxKind.CHARGE_SWORD_HOLD -> bitmap("sword") != null
        else -> bitmap(primaryAsset(request)) != null
    }

    private fun bowAssetsReady(arrowAsset: String, plan: BowVolleyPlan?): Boolean {
        if (!WeaponFxCatalog.BOW_FRAMES.all { bitmap(it) != null }) return false
        if (bitmap(arrowAsset) == null) return false
        return true
    }

    private fun primaryAsset(request: WeaponFxRequest): String = when (request.kind) {
        WeaponFxKind.MELEE_ARC -> WeaponFxCatalog.meleeArcAsset(request.weaponType)
        WeaponFxKind.SPEAR_THRUST -> "spear"
        WeaponFxKind.STAFF_MELEE_ARC, WeaponFxKind.STAFF_SPELL_RISE -> "staff"
        WeaponFxKind.FIRE_PROJECTILE -> "fire_arrow"
        WeaponFxKind.DAGGER_COMBO -> "dagger_r"
        WeaponFxKind.BOW_SHOT -> "bow1"
        WeaponFxKind.CHARGE_SWORD_HOLD -> "sword"
    }

    private fun durationMs(request: WeaponFxRequest): Long {
        val override = request.durationMsOverride
        if (override != null) return override.coerceAtLeast(1L)
        request.bowVolleyPlan?.let { return bowVolleyDurationMs(it) }
        return when (request.kind) {
            WeaponFxKind.MELEE_ARC, WeaponFxKind.STAFF_MELEE_ARC -> MELEE_ARC_MS
            WeaponFxKind.SPEAR_THRUST -> SPEAR_MS
            WeaponFxKind.STAFF_SPELL_RISE -> STAFF_SPELL_MS
            WeaponFxKind.DAGGER_COMBO -> DAGGER_HIT_MS * 2L
            WeaponFxKind.BOW_SHOT, WeaponFxKind.FIRE_PROJECTILE -> singleBowShotMs()
            WeaponFxKind.CHARGE_SWORD_HOLD -> CHARGE_HOLD_MS
        }
    }

    private fun singleBowShotMs(): Long = BOW_DRAW_MS + BOW_HOLD_MS + ARROW_FLIGHT_MS

    private fun bowVolleyDurationMs(plan: BowVolleyPlan): Long {
        var total = 0L
        for (volley in plan.volleys) {
            total += when (volley) {
                is BowVolley.Parallel -> singleBowShotMs()
                is BowVolley.Sequential -> singleBowShotMs() * volley.arrowCount
            }
        }
        return total.coerceAtLeast(1L)
    }

    private fun bitmap(name: String): Bitmap? =
        bitmapCache.getOrPut(name) { loadBitmap("sprites/$name.png") }

    private fun loadBitmap(path: String): Bitmap? =
        runCatching {
            assets.open(path).use { BitmapFactory.decodeStream(it) }
        }.onFailure {
            Log.w(TAG, "Failed to load $path: ${it.message}")
        }.getOrNull()

    // ---- Draw recipes ----

    /**
     * 180° swing: weapon starts raised away from the target and
     * sweeps through a semicircle so the pointy end faces the
     * defender at the impact frame. Pivot = sprite bottom center
     * on the attacker cell center.
     */
    private fun drawMeleeArc(
        canvas: Canvas,
        asset: String,
        pivot: Pair<Float, Float>,
        aimDeg: Float,
        scale: Float,
        elapsed: Long,
        duration: Long,
    ) {
        val bmp = bitmap(asset) ?: return
        val t = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
        val eased = easeOutQuad(t)
        val startAngle = aimDeg + 90f
        val endAngle = aimDeg - 90f
        val angle = lerp(startAngle, endAngle, eased)
        drawWeaponAtPivot(canvas, bmp, pivot.first, pivot.second, angle, scale)
    }

    /** Straight thrust from attacker toward defender. */
    private fun drawThrust(
        canvas: Canvas,
        asset: String,
        attacker: Pair<Float, Float>,
        defender: Pair<Float, Float>,
        aimDeg: Float,
        scale: Float,
        elapsed: Long,
        duration: Long,
    ) {
        val bmp = bitmap(asset) ?: return
        val t = easeOutQuad((elapsed.toFloat() / duration).coerceIn(0f, 1f))
        val reach = hypot(
            defender.first - attacker.first,
            defender.second - attacker.second,
        ) * 0.55f
        val px = attacker.first + cos(Math.toRadians(aimDeg.toDouble())).toFloat() * reach * t
        val py = attacker.second + sin(Math.toRadians(aimDeg.toDouble())).toFloat() * reach * t
        drawWeaponAtPivot(canvas, bmp, px, py, aimDeg, scale)
    }

    /** Staff rises slowly while a soft glow pulses at the pivot. */
    private fun drawStaffSpell(
        canvas: Canvas,
        pivot: Pair<Float, Float>,
        aimDeg: Float,
        scale: Float,
        elapsed: Long,
        duration: Long,
        density: Float,
    ) {
        val bmp = bitmap("staff") ?: return
        val t = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
        val rise = cellPxEquivalent(scale) * 0.35f * easeInOutQuad(t)
        val glowR = scale * 0.55f * (0.6f + 0.4f * sin(t * Math.PI.toFloat() * 3f))
        staffGlowPaint.alpha = (80 + 60 * sin(t * Math.PI.toFloat() * 4f)).toInt().coerceIn(40, 140)
        canvas.drawCircle(pivot.first, pivot.second - rise * 0.5f, glowR, staffGlowPaint)
        drawWeaponAtPivot(canvas, bmp, pivot.first, pivot.second - rise, aimDeg, scale)
    }

    /** dagger_r thrust, then dagger_l thrust along the aim line. */
    private fun drawDaggerCombo(
        canvas: Canvas,
        attacker: Pair<Float, Float>,
        defender: Pair<Float, Float>,
        aimDeg: Float,
        scale: Float,
        elapsed: Long,
    ) {
        val reach = hypot(
            defender.first - attacker.first,
            defender.second - attacker.second,
        ) * 0.42f
        val phaseMs = DAGGER_HIT_MS
        if (elapsed < phaseMs) {
            val t = easeOutQuad((elapsed.toFloat() / phaseMs).coerceIn(0f, 1f))
            val px = attacker.first + cos(Math.toRadians(aimDeg.toDouble())).toFloat() * reach * t
            val py = attacker.second + sin(Math.toRadians(aimDeg.toDouble())).toFloat() * reach * t
            bitmap("dagger_r")?.let { drawWeaponAtPivot(canvas, it, px, py, aimDeg, scale * 0.9f) }
        } else {
            val local = elapsed - phaseMs
            val t = easeOutQuad((local.toFloat() / phaseMs).coerceIn(0f, 1f))
            val px = attacker.first + cos(Math.toRadians(aimDeg.toDouble())).toFloat() * reach * t
            val py = attacker.second + sin(Math.toRadians(aimDeg.toDouble())).toFloat() * reach * t
            bitmap("dagger_l")?.let { drawWeaponAtPivot(canvas, it, px, py, aimDeg, scale * 0.9f) }
        }
    }

    /**
     * Plays [plan] back-to-back: parallel volleys draw once and launch
     * offset arrows together; sequential volleys repeat the full cycle.
     */
    private fun drawBowVolleys(
        canvas: Canvas,
        attacker: Pair<Float, Float>,
        defender: Pair<Float, Float>,
        aimDeg: Float,
        scale: Float,
        cellPx: Float,
        elapsed: Long,
        plan: BowVolleyPlan,
    ) {
        var remaining = elapsed
        for (volley in plan.volleys) {
            val volleyDuration = when (volley) {
                is BowVolley.Parallel -> singleBowShotMs()
                is BowVolley.Sequential -> singleBowShotMs() * volley.arrowCount
            }
            if (remaining < volleyDuration) {
                when (volley) {
                    is BowVolley.Parallel -> drawBowParallelVolley(
                        canvas = canvas,
                        attacker = attacker,
                        defender = defender,
                        aimDeg = aimDeg,
                        scale = scale,
                        cellPx = cellPx,
                        elapsed = remaining,
                        arrowCount = volley.arrowCount,
                        arrowAsset = plan.arrowAsset,
                    )
                    is BowVolley.Sequential -> {
                        val shotIndex = (remaining / singleBowShotMs()).toInt()
                            .coerceIn(0, volley.arrowCount - 1)
                        val shotElapsed = remaining - shotIndex * singleBowShotMs()
                        drawBowShot(
                            canvas = canvas,
                            attacker = attacker,
                            defender = defender,
                            aimDeg = aimDeg,
                            scale = scale,
                            cellPx = cellPx,
                            elapsed = shotElapsed,
                            arrowAsset = plan.arrowAsset,
                        )
                    }
                }
                return
            }
            remaining -= volleyDuration
        }
    }

    /**
     * One draw cycle; [arrowCount] projectiles fly in parallel with a
     * slight lateral spread perpendicular to the aim line.
     */
    private fun drawBowParallelVolley(
        canvas: Canvas,
        attacker: Pair<Float, Float>,
        defender: Pair<Float, Float>,
        aimDeg: Float,
        scale: Float,
        cellPx: Float,
        elapsed: Long,
        arrowCount: Int,
        arrowAsset: String,
    ) {
        drawBowShot(
            canvas = canvas,
            attacker = attacker,
            defender = defender,
            aimDeg = aimDeg,
            scale = scale,
            cellPx = cellPx,
            elapsed = elapsed,
            arrowAsset = arrowAsset,
            drawArrows = false,
        )

        val arrowBmp = bitmap(arrowAsset) ?: return
        val aimRad = Math.toRadians(aimDeg.toDouble())
        val perpX = -sin(aimRad).toFloat()
        val perpY = cos(aimRad).toFloat()
        val spread = cellPx * 0.14f

        if (elapsed < BOW_DRAW_MS + BOW_HOLD_MS) {
            for (i in 0 until arrowCount) {
                val lane = parallelLaneOffset(i, arrowCount)
                val backOffset = bowDrawBackOffset(elapsed, cellPx)
                val ax = attacker.first - cos(aimRad).toFloat() * backOffset + perpX * spread * lane
                val ay = attacker.second - sin(aimRad).toFloat() * backOffset + perpY * spread * lane
                drawWeaponAtPivot(canvas, arrowBmp, ax, ay, aimDeg, scale * 0.85f)
            }
            return
        }

        val flightStart = BOW_DRAW_MS + BOW_HOLD_MS
        val local = elapsed - flightStart
        val t = (local.toFloat() / ARROW_FLIGHT_MS).coerceIn(0f, 1f)
        val eased = easeInQuad(t)
        for (i in 0 until arrowCount) {
            val lane = parallelLaneOffset(i, arrowCount)
            val baseX = lerp(attacker.first, defender.first, eased)
            val baseY = lerp(attacker.second, defender.second, eased)
            val ax = baseX + perpX * spread * lane
            val ay = baseY + perpY * spread * lane
            drawWeaponAtPivot(canvas, arrowBmp, ax, ay, aimDeg, scale * 0.85f)
        }
    }

    /** Centers parallel arrows around the aim line (-0.5, +0.5 for a pair). */
    private fun parallelLaneOffset(index: Int, count: Int): Float {
        if (count <= 1) return 0f
        return index - (count - 1) / 2f
    }

    private fun bowDrawBackOffset(elapsed: Long, cellPx: Float): Float = when {
        elapsed < BOW_FRAME_1_MS -> 0f
        elapsed < BOW_FRAME_1_MS + BOW_FRAME_2_MS -> cellPx * 0.06f
        elapsed < BOW_DRAW_MS -> cellPx * 0.14f
        else -> 0f
    }

    /**
     * bow1 (0.25s) -> bow2 (0.25s) -> bow3 (0.5s) -> bow1 hold while
     * [arrowAsset] flies to the defender. The projectile sits on the bow and
     * eases backward through the draw frames.
     */
    private fun drawBowShot(
        canvas: Canvas,
        attacker: Pair<Float, Float>,
        defender: Pair<Float, Float>,
        aimDeg: Float,
        scale: Float,
        cellPx: Float,
        elapsed: Long,
        arrowAsset: String,
        drawArrows: Boolean = true,
    ) {
        val bowFrame = when {
            elapsed < BOW_FRAME_1_MS -> "bow1"
            elapsed < BOW_FRAME_1_MS + BOW_FRAME_2_MS -> "bow2"
            elapsed < BOW_DRAW_MS -> "bow3"
            else -> "bow1"
        }
        val bowBmp = bitmap(bowFrame) ?: return
        drawWeaponAtPivot(canvas, bowBmp, attacker.first, attacker.second, aimDeg, scale)

        if (!drawArrows) return

        val arrowBmp = bitmap(arrowAsset) ?: return
        val aimRad = Math.toRadians(aimDeg.toDouble())
        val backOffset = bowDrawBackOffset(elapsed, cellPx)
        if (elapsed < BOW_DRAW_MS + BOW_HOLD_MS) {
            val ax = attacker.first - cos(aimRad).toFloat() * backOffset
            val ay = attacker.second - sin(aimRad).toFloat() * backOffset
            drawWeaponAtPivot(canvas, arrowBmp, ax, ay, aimDeg, scale * 0.85f)
        } else {
            val flightStart = BOW_DRAW_MS + BOW_HOLD_MS
            val local = elapsed - flightStart
            val t = (local.toFloat() / ARROW_FLIGHT_MS).coerceIn(0f, 1f)
            val eased = easeInQuad(t)
            val ax = lerp(attacker.first, defender.first, eased)
            val ay = lerp(attacker.second, defender.second, eased)
            drawWeaponAtPivot(canvas, arrowBmp, ax, ay, aimDeg, scale * 0.85f)
        }
    }

    /**
     * Draws [bmp] with its bottom center at ([pivotX], [pivotY]),
     * rotated so the sprite's authored "up" points along [aimDeg].
     */
    private fun drawWeaponAtPivot(
        canvas: Canvas,
        bmp: Bitmap,
        pivotX: Float,
        pivotY: Float,
        aimDeg: Float,
        targetHeight: Float,
    ) {
        val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
        val h = targetHeight
        val w = h * aspect
        val rotation = aimDeg + SPRITE_UP_OFFSET_DEG

        canvas.save()
        canvas.translate(pivotX, pivotY)
        canvas.rotate(rotation)
        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRect.set(-w / 2f, -h, w / 2f, 0f)
        canvas.drawBitmap(bmp, srcRect, dstRect, drawPaint)
        canvas.restore()
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
        return Pair(sx + cellPx / 2f, sy + cellPx / 2f)
    }

    /** Degrees from attacker to defender (0 = east, 90 = south). */
    private fun aimDegrees(ax: Float, ay: Float, dx: Float, dy: Float): Float =
        Math.toDegrees(atan2((dy - ay).toDouble(), (dx - ax).toDouble())).toFloat()

    private fun cellPxEquivalent(scale: Float): Float = scale / SPRITE_HEIGHT_FRACTION

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun easeOutQuad(t: Float): Float = 1f - (1f - t) * (1f - t)
    private fun easeInQuad(t: Float): Float = t * t
    private fun easeInOutQuad(t: Float): Float =
        if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f).let { it * it } / 2f

    companion object {
        private const val TAG = "WeaponAttackFxPlayer"

        /** Sprite height as a fraction of one cell. */
        private const val SPRITE_HEIGHT_FRACTION = 0.82f

        /**
         * Art is authored point-up (-90° from +X). Add 90° so
         * "up" in the bitmap aligns with [aimDeg].
         */
        private const val SPRITE_UP_OFFSET_DEG = 90f

        private const val MELEE_ARC_MS = 420L
        private const val SPEAR_MS = 360L
        private const val STAFF_SPELL_MS = 880L
        private const val DAGGER_HIT_MS = 260L
        private const val BOW_FRAME_1_MS = 250L
        private const val BOW_FRAME_2_MS = 250L
        private const val BOW_FRAME_3_MS = 500L
        private const val BOW_DRAW_MS = BOW_FRAME_1_MS + BOW_FRAME_2_MS + BOW_FRAME_3_MS
        private const val BOW_HOLD_MS = 120L
        private const val ARROW_FLIGHT_MS = 380L
        private const val CHARGE_HOLD_MS = 280L
    }
}
