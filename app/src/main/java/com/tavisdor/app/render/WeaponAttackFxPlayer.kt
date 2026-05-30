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
import com.tavisdor.app.render.UtilityCastFxCatalog.introDurationMs
import com.tavisdor.app.render.UtilityCastMotion
import kotlin.math.PI
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
    private var bowImpactsFired: Int = 0

    val isActive: Boolean get() = activeRequest != null

    val playbackRequest: WeaponFxRequest? get() = activeRequest

    val playbackElapsedMs: Long get() = elapsedMs

    fun start(request: WeaponFxRequest, onComplete: () -> Unit): Boolean {
        if (!canPlay(request)) return false
        cancel()
        activeRequest = request
        this.onComplete = onComplete
        elapsedMs = 0L
        bowImpactsFired = 0
        return true
    }

    fun cancel() {
        activeRequest = null
        onComplete = null
        elapsedMs = 0L
        bowImpactsFired = 0
    }

    /**
     * Advances playback. Returns true while the animation is still
     * running (host should keep invalidating).
     */
    fun tick(deltaMs: Long): Boolean {
        val request = activeRequest ?: return false
        elapsedMs += deltaMs.coerceAtLeast(0L)
        processBowShotImpacts(elapsedMs, request)
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
            WeaponFxKind.DOUBLE_STRIKE_THRUST -> {
                drawDoubleStrikeThrust(canvas, attacker, defender, aimDeg, scale, elapsedMs)
            }
            WeaponFxKind.STAFF_SPELL_RISE -> {
                val partyScreen = attacker
                val partyPivot = if (request.castFromPartyIcon) {
                    partyIconCenter(partyScreen.first, partyScreen.second, cellPx)
                } else {
                    partyScreen
                }
                val focusCenter = request.utilityFocusCell?.let {
                    cellCenter(it, camCx, camCy, cellPx, viewCx, viewCy)
                }
                if (request.utilityMotion != null) {
                    drawUtilityCast(
                        canvas = canvas,
                        motion = request.utilityMotion,
                        partyPivot = partyPivot,
                        partyCellCenter = partyScreen,
                        focusCenter = focusCenter,
                        cellPx = cellPx,
                        spriteScale = scale,
                        flowSequence = request.flowFrameSequence,
                        flowStepMs = request.flowStepMs,
                        flowHeightScale = request.flowHeightScale,
                        elapsed = elapsedMs,
                    )
                } else {
                    drawStaffSpellCast(
                        canvas = canvas,
                        pivot = partyPivot,
                        staffHeight = scale,
                        flowFrames = request.spellFlowFrames,
                        flowSequence = request.flowFrameSequence,
                        flowStepMs = request.flowStepMs,
                        flowHeightScale = request.flowHeightScale,
                        showStaff = request.showStaffDuringCast,
                        elapsed = elapsedMs,
                        duration = durationMs(request),
                    )
                }
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
        val request = activeRequest
        if (request != null) {
            flushRemainingBowShotImpacts(request)
        }
        val cb = onComplete
        activeRequest = null
        onComplete = null
        elapsedMs = 0L
        bowImpactsFired = 0
        cb?.invoke()
    }

    /**
     * Fires [WeaponFxRequest.onBowShotImpact] handlers when each arrow's
     * flight reaches the defender (one dodge / damage roll per arrow).
     */
    private fun processBowShotImpacts(elapsed: Long, request: WeaponFxRequest) {
        val handlers = request.onBowShotImpact ?: return
        val plan = request.bowVolleyPlan ?: return
        val times = bowShotImpactTimesMs(plan)
        while (bowImpactsFired < handlers.size && bowImpactsFired < times.size &&
            elapsed >= times[bowImpactsFired]
        ) {
            handlers[bowImpactsFired].invoke()
            bowImpactsFired++
        }
    }

    private fun flushRemainingBowShotImpacts(request: WeaponFxRequest) {
        val handlers = request.onBowShotImpact ?: return
        while (bowImpactsFired < handlers.size) {
            handlers[bowImpactsFired].invoke()
            bowImpactsFired++
        }
    }

    private fun bowShotImpactTimesMs(plan: BowVolleyPlan): List<Long> {
        val base = singleBowShotMs()
        val impactInShot = BOW_DRAW_MS + BOW_HOLD_MS + ARROW_FLIGHT_MS
        val times = mutableListOf<Long>()
        var volleyStart = 0L
        for (volley in plan.volleys) {
            when (volley) {
                is BowVolley.Parallel -> {
                    val impactAt = volleyStart + impactInShot.coerceAtMost(base)
                    repeat(volley.arrowCount) {
                        times += impactAt
                    }
                    volleyStart += base
                }
                is BowVolley.Sequential -> {
                    var shotStart = volleyStart
                    for (shotIndex in 0 until volley.arrowCount) {
                        val shotDur = volley.shotDurationMs(shotIndex, base)
                        val scaledImpact = if (shotDur >= impactInShot) {
                            impactInShot
                        } else {
                            (impactInShot * shotDur / base).coerceAtLeast(1L)
                        }
                        times += shotStart + scaledImpact
                        shotStart += shotDur
                    }
                    volleyStart += volley.totalDurationMs(base)
                }
            }
        }
        return times
    }

    private fun canPlay(request: WeaponFxRequest): Boolean = when (request.kind) {
        WeaponFxKind.DAGGER_COMBO ->
            bitmap("dagger_r") != null && bitmap("dagger_l") != null
        WeaponFxKind.DOUBLE_STRIKE_THRUST ->
            bitmap("doubls1") != null && bitmap("doubls2") != null
        WeaponFxKind.BOW_SHOT -> bowAssetsReady("arrow", request.bowVolleyPlan)
        WeaponFxKind.FIRE_PROJECTILE -> bowAssetsReady("fire_arrow", request.bowVolleyPlan)
        WeaponFxKind.CHARGE_SWORD_HOLD -> bitmap("sword") != null
        WeaponFxKind.STAFF_SPELL_RISE ->
            if (request.showStaffDuringCast) {
                bitmap("staff") != null && staffFlowAssetsReady(request)
            } else {
                staffFlowAssetsReady(request)
            }
        else -> bitmap(primaryAsset(request)) != null
    }

    private fun staffFlowAssetsReady(request: WeaponFxRequest): Boolean {
        if (request.flowFrameSequence.isNotEmpty()) {
            return request.flowFrameSequence.all { bitmap(it) != null }
        }
        if (request.spellFlowFrames.isNotEmpty()) {
            return request.spellFlowFrames.all { bitmap(it) != null }
        }
        return true
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
        WeaponFxKind.DOUBLE_STRIKE_THRUST -> "doubls1"
        WeaponFxKind.BOW_SHOT -> "bow1"
        WeaponFxKind.CHARGE_SWORD_HOLD -> "sword"
    }

    private fun durationMs(request: WeaponFxRequest): Long {
        val override = request.durationMsOverride
        if (override != null) return override.coerceAtLeast(1L)
        if (request.flowFrameSequence.isNotEmpty()) {
            return request.flowFrameSequence.size * request.flowStepMs.coerceAtLeast(1L)
        }
        request.bowVolleyPlan?.let { return bowVolleyDurationMs(it) }
        return when (request.kind) {
            WeaponFxKind.MELEE_ARC, WeaponFxKind.STAFF_MELEE_ARC -> MELEE_ARC_MS
            WeaponFxKind.SPEAR_THRUST -> SPEAR_MS
            WeaponFxKind.STAFF_SPELL_RISE -> STAFF_SPELL_MS
            WeaponFxKind.DAGGER_COMBO -> DAGGER_HIT_MS * 2L
            WeaponFxKind.DOUBLE_STRIKE_THRUST -> DOUBLE_STRIKE_PHASE_MS * 2L
            WeaponFxKind.BOW_SHOT, WeaponFxKind.FIRE_PROJECTILE -> singleBowShotMs()
            WeaponFxKind.CHARGE_SWORD_HOLD -> CHARGE_HOLD_MS
        }
    }

    private fun singleBowShotMs(): Long = BOW_DRAW_MS + BOW_HOLD_MS + ARROW_FLIGHT_MS

    private fun bowVolleyDurationMs(plan: BowVolleyPlan): Long {
        val base = singleBowShotMs()
        var total = 0L
        for (volley in plan.volleys) {
            total += when (volley) {
                is BowVolley.Parallel -> base
                is BowVolley.Sequential -> volley.totalDurationMs(base)
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

    private fun drawUtilityCast(
        canvas: Canvas,
        motion: UtilityCastMotion,
        partyPivot: Pair<Float, Float>,
        partyCellCenter: Pair<Float, Float>,
        focusCenter: Pair<Float, Float>?,
        cellPx: Float,
        spriteScale: Float,
        flowSequence: List<String>,
        flowStepMs: Long,
        flowHeightScale: Float,
        elapsed: Long,
    ) {
        if (flowSequence.isEmpty()) return
        val flowH = spriteScale * SPELL_FLOW_HEIGHT_FRACTION * flowHeightScale
        val introMs = introDurationMs(motion)
        val asset = if (elapsed < introMs) {
            flowSequence.first()
        } else {
            val cycleElapsed = elapsed - introMs
            val idx = (cycleElapsed / flowStepMs.coerceAtLeast(1L)).toInt()
                .coerceIn(0, flowSequence.lastIndex)
            flowSequence[idx]
        }
        val bmp = bitmap(asset) ?: return
        val dest = focusCenter ?: partyCellCenter
        val (anchorX, anchorY, centerAnchored) = utilitySpriteAnchor(
            motion = motion,
            elapsed = elapsed,
            partyPivot = partyPivot,
            partyCellCenter = partyCellCenter,
            focusCenter = dest,
            cellPx = cellPx,
            flowH = flowH,
        )
        if (centerAnchored) {
            drawBitmapCentered(canvas, bmp, anchorX, anchorY, flowH)
        } else {
            drawBitmapUpright(canvas, bmp, anchorX, anchorY, flowH)
        }
    }

    private fun utilitySpriteAnchor(
        motion: UtilityCastMotion,
        elapsed: Long,
        partyPivot: Pair<Float, Float>,
        partyCellCenter: Pair<Float, Float>,
        focusCenter: Pair<Float, Float>,
        cellPx: Float,
        flowH: Float,
    ): Triple<Float, Float, Boolean> {
        when (motion) {
            UtilityCastMotion.CAMP_SLIDE_THEN_CYCLE -> {
                val partyFoot = partyIconRestingBase(
                    partyCellCenter.first,
                    partyCellCenter.second,
                    cellPx,
                )
                val travelMs = UtilityCastFxCatalog.CAMP_TRAVEL_MS
                if (elapsed < travelMs) {
                    val t = easeInOutQuad(
                        (elapsed.toFloat() / travelMs.toFloat()).coerceIn(0f, 1f),
                    )
                    val startCenterY = partyFoot.second - flowH / 2f
                    val cx = partyFoot.first + (focusCenter.first - partyFoot.first) * t
                    val cy = startCenterY + (focusCenter.second - startCenterY) * t
                    return Triple(cx, cy, true)
                }
                return Triple(focusCenter.first, focusCenter.second, true)
            }
            UtilityCastMotion.RISE_THEN_CYCLE -> {
                val holdMs = UtilityCastFxCatalog.RISE_INTRO_HOLD_MS
                val riseMs = UtilityCastFxCatalog.RISE_INTRO_ASCENT_MS
                val iconHeight = cellPx * PARTY_ICON_HEIGHT_FRACTION
                val riseDistance = iconHeight * UTILITY_RISE_ICON_HEIGHT_MULTIPLIER
                val risenCenterY = partyPivot.second - riseDistance
                val centerY = when {
                    elapsed < holdMs -> partyPivot.second
                    elapsed < holdMs + riseMs -> {
                        val t = easeInOutQuad(
                            ((elapsed - holdMs).toFloat() / riseMs.toFloat()).coerceIn(0f, 1f),
                        )
                        partyPivot.second - riseDistance * t
                    }
                    else -> risenCenterY
                }
                return Triple(partyPivot.first, centerY, true)
            }
        }
    }

    private fun partyIconRestingBase(
        cellCenterX: Float,
        cellCenterY: Float,
        cellPx: Float,
    ): Pair<Float, Float> {
        val baseOffset = cellPx * PARTY_ICON_BASE_OFFSET_FRACTION
        val cellTop = cellCenterY - cellPx * 0.5f
        val restingBaseY = cellTop + cellPx - baseOffset
        return cellCenterX to restingBaseY
    }

    /**
     * Gandalf-style cast: [staff.png] stays upright, rises from the
     * party icon center to ~98% of its drawn height while flow
     * frames cycle at the staff tip.
     */
    private fun drawStaffSpellCast(
        canvas: Canvas,
        pivot: Pair<Float, Float>,
        staffHeight: Float,
        flowFrames: List<String>,
        flowSequence: List<String>,
        flowStepMs: Long,
        flowHeightScale: Float,
        showStaff: Boolean,
        elapsed: Long,
        duration: Long,
    ) {
        val flowH = staffHeight * SPELL_FLOW_HEIGHT_FRACTION * flowHeightScale
        val flowAnchorY: Float
        val flowAnchorX = pivot.first

        if (showStaff) {
            val staffBmp = bitmap("staff") ?: return
            val t = (elapsed.toFloat() / duration.coerceAtLeast(1L)).coerceIn(0f, 1f)
            val eased = easeInOutQuad(t)
            val riseDistance = staffHeight * STAFF_CAST_RISE_HEIGHT_MULTIPLIER * eased
            val pivotY = pivot.second - riseDistance
            drawBitmapUpright(canvas, staffBmp, pivot.first, pivotY, staffHeight)
            flowAnchorY = pivotY - staffHeight
        } else {
            flowAnchorY = pivot.second
        }

        val t = (elapsed.toFloat() / duration.coerceAtLeast(1L)).coerceIn(0f, 1f)
        val sequenceAsset = if (flowSequence.isNotEmpty()) {
            val idx = (elapsed / flowStepMs.coerceAtLeast(1L)).toInt()
                .coerceIn(0, flowSequence.lastIndex)
            flowSequence[idx]
        } else {
            null
        }
        val flowBmp = when {
            sequenceAsset != null -> bitmap(sequenceAsset)
            else -> {
                val loadedFlow = flowFrames.mapNotNull { bitmap(it) }
                if (loadedFlow.isEmpty()) null else {
                    val frameIdx = (
                        (elapsed / SPELL_FLOW_FRAME_MS).toInt() % loadedFlow.size + loadedFlow.size
                        ) % loadedFlow.size
                    loadedFlow[frameIdx]
                }
            }
        }
        if (flowBmp != null) {
            drawBitmapUpright(canvas, flowBmp, flowAnchorX, flowAnchorY, flowH)
        } else if (showStaff) {
            val glowR = staffHeight * 0.22f * (0.7f + 0.3f * sin(t * Math.PI.toFloat() * 4f))
            staffGlowPaint.alpha = (70 + 50 * sin(t * Math.PI.toFloat() * 5f)).toInt().coerceIn(35, 130)
            canvas.drawCircle(flowAnchorX, flowAnchorY, glowR, staffGlowPaint)
        }
    }

    /** Matches [DungeonRenderer] party token vertical placement. */
    private fun partyIconCenter(cellCenterX: Float, cellCenterY: Float, cellPx: Float): Pair<Float, Float> {
        val baseOffset = cellPx * PARTY_ICON_BASE_OFFSET_FRACTION
        val iconHeight = cellPx * PARTY_ICON_HEIGHT_FRACTION
        val cellTop = cellCenterY - cellPx * 0.5f
        val restingBaseY = cellTop + cellPx - baseOffset
        val iconTop = restingBaseY - iconHeight
        val centerY = (iconTop + restingBaseY) * 0.5f
        return cellCenterX to centerY
    }

    /**
     * Draws [bmp] upright (file orientation) with its bottom center at
     * ([pivotX], [pivotY]).
     */
    private fun drawBitmapUpright(
        canvas: Canvas,
        bmp: Bitmap,
        pivotX: Float,
        pivotY: Float,
        targetHeight: Float,
    ) {
        val aspect = bmp.width.toFloat() / bmp.height.coerceAtLeast(1)
        val h = targetHeight
        val w = h * aspect
        canvas.save()
        canvas.translate(pivotX, pivotY)
        srcRect.set(0, 0, bmp.width, bmp.height)
        dstRect.set(-w / 2f, -h, w / 2f, 0f)
        canvas.drawBitmap(bmp, srcRect, dstRect, drawPaint)
        canvas.restore()
    }

    /** Draws [bmp] centered on ([centerX], [centerY]). */
    private fun drawBitmapCentered(
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

    /** doubls1 thrust, then doubls2 — same motion as [drawThrust] / spear. */
    private fun drawDoubleStrikeThrust(
        canvas: Canvas,
        attacker: Pair<Float, Float>,
        defender: Pair<Float, Float>,
        aimDeg: Float,
        scale: Float,
        elapsed: Long,
    ) {
        val phaseMs = DOUBLE_STRIKE_PHASE_MS
        val asset = if (elapsed < phaseMs) "doubls1" else "doubls2"
        val phaseElapsed = if (elapsed < phaseMs) elapsed else elapsed - phaseMs
        drawThrust(canvas, asset, attacker, defender, aimDeg, scale, phaseElapsed, phaseMs)
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
        val baseShotMs = singleBowShotMs()
        var remaining = elapsed
        for (volley in plan.volleys) {
            val volleyDuration = when (volley) {
                is BowVolley.Parallel -> baseShotMs
                is BowVolley.Sequential -> volley.totalDurationMs(baseShotMs)
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
                        var shotStart = 0L
                        for (shotIndex in 0 until volley.arrowCount) {
                            val shotDur = volley.shotDurationMs(shotIndex, baseShotMs)
                            if (remaining < shotStart + shotDur) {
                                drawBowShot(
                                    canvas = canvas,
                                    attacker = attacker,
                                    defender = defender,
                                    aimDeg = aimDeg,
                                    scale = scale,
                                    cellPx = cellPx,
                                    elapsed = remaining - shotStart,
                                    arrowAsset = plan.arrowAsset,
                                    shotDurationMs = shotDur,
                                    shotIndex = shotIndex,
                                    shotCount = volley.arrowCount,
                                )
                                return
                            }
                            shotStart += shotDur
                        }
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
        val alongX = cos(aimRad).toFloat()
        val alongY = sin(aimRad).toFloat()
        val (perpX, perpY) = perpUnit(aimDeg)

        if (elapsed < BOW_DRAW_MS + BOW_HOLD_MS) {
            for (i in 0 until arrowCount) {
                val variation = arrowFlightVariation(i, arrowCount)
                val lateral = lateralOffsetAt(flightProgress = 0f, cellPx = cellPx, variation = variation)
                val backOffset = bowDrawBackOffset(elapsed, cellPx)
                val ax = attacker.first - alongX * backOffset + perpX * lateral
                val ay = attacker.second - alongY * backOffset + perpY * lateral
                drawWeaponAtPivot(
                    canvas, arrowBmp, ax, ay,
                    aimDeg + variation.aimJitterDeg * 0.25f,
                    scale * 0.85f,
                )
            }
            return
        }

        val flightStart = BOW_DRAW_MS + BOW_HOLD_MS
        val local = elapsed - flightStart
        for (i in 0 until arrowCount) {
            val variation = arrowFlightVariation(i, arrowCount)
            val delayMs = (ARROW_FLIGHT_MS * variation.flightDelayFrac).toLong()
            val arrowLocal = local - delayMs
            val flightProgress = if (arrowLocal < 0L) {
                0f
            } else {
                (arrowLocal.toFloat() / ARROW_FLIGHT_MS).coerceIn(0f, 1f)
            }
            val (ax, ay) = arrowPositionOnPath(
                attacker = attacker,
                defender = defender,
                aimDeg = aimDeg,
                flightProgress = flightProgress,
                cellPx = cellPx,
                variation = variation,
            )
            drawWeaponAtPivot(
                canvas, arrowBmp, ax, ay,
                aimDeg + variation.aimJitterDeg * flightProgress,
                scale * 0.85f,
            )
        }
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
        shotDurationMs: Long = singleBowShotMs(),
        shotIndex: Int = 0,
        shotCount: Int = 1,
    ) {
        val timelineMs = mapToFullShotTimeline(elapsed, shotDurationMs)
        val variation = arrowFlightVariation(shotIndex, shotCount)
        val bowFrame = when {
            timelineMs < BOW_FRAME_1_MS -> "bow1"
            timelineMs < BOW_FRAME_1_MS + BOW_FRAME_2_MS -> "bow2"
            timelineMs < BOW_DRAW_MS -> "bow3"
            else -> "bow1"
        }
        val bowBmp = bitmap(bowFrame) ?: return
        drawWeaponAtPivot(canvas, bowBmp, attacker.first, attacker.second, aimDeg, scale)

        if (!drawArrows) return

        val arrowBmp = bitmap(arrowAsset) ?: return
        val aimRad = Math.toRadians(aimDeg.toDouble())
        val alongX = cos(aimRad).toFloat()
        val alongY = sin(aimRad).toFloat()
        val (perpX, perpY) = perpUnit(aimDeg)
        val backOffset = bowDrawBackOffset(timelineMs, cellPx)
        if (timelineMs < BOW_DRAW_MS + BOW_HOLD_MS) {
            val lateral = lateralOffsetAt(0f, cellPx, variation)
            val ax = attacker.first - alongX * backOffset + perpX * lateral
            val ay = attacker.second - alongY * backOffset + perpY * lateral
            drawWeaponAtPivot(
                canvas, arrowBmp, ax, ay,
                aimDeg + variation.aimJitterDeg * 0.25f,
                scale * 0.85f,
            )
        } else {
            val flightStart = BOW_DRAW_MS + BOW_HOLD_MS
            val local = timelineMs - flightStart
            val flightProgress = (local.toFloat() / ARROW_FLIGHT_MS).coerceIn(0f, 1f)
            val (ax, ay) = arrowPositionOnPath(
                attacker, defender, aimDeg, flightProgress, cellPx, variation,
            )
            drawWeaponAtPivot(
                canvas, arrowBmp, ax, ay,
                aimDeg + variation.aimJitterDeg * flightProgress,
                scale * 0.85f,
            )
        }
    }

    /** Deterministic spread / stagger for multi-arrow volleys (not RNG per frame). */
    private data class ArrowFlightVariation(
        val lane: Float,
        val flightDelayFrac: Float,
        val wobblePhase: Float,
        val aimJitterDeg: Float,
    )

    private fun arrowFlightVariation(index: Int, count: Int): ArrowFlightVariation {
        if (count <= 1) {
            return ArrowFlightVariation(0f, 0f, 0f, 0f)
        }
        return when (count) {
            2 -> when (index) {
                0 -> ArrowFlightVariation(-0.62f, 0f, 0.35f, -3f)
                else -> ArrowFlightVariation(0.62f, 0.09f, 1.25f, 3f)
            }
            else -> when (index) {
                0 -> ArrowFlightVariation(-0.78f, 0f, 0.15f, -3.5f)
                1 -> ArrowFlightVariation(0.08f, 0.05f, 0.95f, 1f)
                else -> ArrowFlightVariation(0.82f, 0.1f, 1.75f, 4f)
            }
        }
    }

    private fun perpUnit(aimDeg: Float): Pair<Float, Float> {
        val aimRad = Math.toRadians(aimDeg.toDouble())
        return -sin(aimRad).toFloat() to cos(aimRad).toFloat()
    }

    /** Lateral offset perpendicular to aim; wobbles mid-flight so paths do not stay parallel. */
    private fun lateralOffsetAt(
        flightProgress: Float,
        cellPx: Float,
        variation: ArrowFlightVariation,
    ): Float {
        val spread = cellPx * 0.17f
        val base = variation.lane * spread
        val wobble = sin((flightProgress * PI + variation.wobblePhase).toFloat()) * cellPx * 0.06f
        return base + wobble
    }

    private fun arrowPositionOnPath(
        attacker: Pair<Float, Float>,
        defender: Pair<Float, Float>,
        aimDeg: Float,
        flightProgress: Float,
        cellPx: Float,
        variation: ArrowFlightVariation,
    ): Pair<Float, Float> {
        val eased = easeInQuad(flightProgress.coerceIn(0f, 1f))
        val (perpX, perpY) = perpUnit(aimDeg)
        val lateral = lateralOffsetAt(flightProgress, cellPx, variation)
        val baseX = lerp(attacker.first, defender.first, eased)
        val baseY = lerp(attacker.second, defender.second, eased)
        return baseX + perpX * lateral to baseY + perpY * lateral
    }

    /** Compresses a shortened shot into the full draw / flight timeline. */
    private fun mapToFullShotTimeline(elapsed: Long, shotDurationMs: Long): Long {
        if (shotDurationMs >= singleBowShotMs()) return elapsed
        val progress = (elapsed.toFloat() / shotDurationMs.toFloat()).coerceIn(0f, 1f)
        return (progress * singleBowShotMs()).toLong().coerceIn(0L, singleBowShotMs() - 1)
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
        private const val STAFF_SPELL_MS = 1150L
        /** Rise distance = staff draw height × this (0.4875 = prior 0.975 × 0.5). */
        private const val STAFF_CAST_RISE_HEIGHT_MULTIPLIER = 0.4875f

        /** Utility hold+rise: vertical travel as a multiple of party icon height. */
        private const val UTILITY_RISE_ICON_HEIGHT_MULTIPLIER = 0.675f
        private const val SPELL_FLOW_FRAME_MS = 180L
        private const val SPELL_FLOW_HEIGHT_FRACTION = 0.55f

        private const val PARTY_ICON_WIDTH_FRACTION = 1.1f
        private const val PARTY_ICON_HEIGHT_FRACTION = 1.298f
        private const val PARTY_ICON_BASE_OFFSET_FRACTION = 0.18f
        private const val DAGGER_HIT_MS = 260L
        private const val DOUBLE_STRIKE_PHASE_MS = SPEAR_MS
        private const val BOW_FRAME_1_MS = 250L
        private const val BOW_FRAME_2_MS = 250L
        private const val BOW_FRAME_3_MS = 500L
        private const val BOW_DRAW_MS = BOW_FRAME_1_MS + BOW_FRAME_2_MS + BOW_FRAME_3_MS
        private const val BOW_HOLD_MS = 120L
        private const val ARROW_FLIGHT_MS = 380L
        private const val CHARGE_HOLD_MS = 280L
    }
}
