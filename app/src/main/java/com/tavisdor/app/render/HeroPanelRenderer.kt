package com.tavisdor.app.render

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.util.Log
import com.tavisdor.app.combat.HateTracker
import com.tavisdor.app.enemies.Enemy
import com.tavisdor.app.game.Game
import com.tavisdor.app.party.Gender
import com.tavisdor.app.party.Hero
import com.tavisdor.app.party.HeroClass
import com.tavisdor.app.skills.Skill
import com.tavisdor.app.skills.SkillButton
import kotlin.math.min
/**
 * Draws the persistent bottom 2x2 hero panel inside
 * [com.tavisdor.app.HeroPanelView]. Top row = front line (slots 0, 1),
 * bottom row = back line (slots 2, 3).
 *
 * Each hero cell uses this layout:
 *
 *   +---------+---------------------------+
 *   Name - Class          Threat Level: N   <- label row (combat)
 *   +---------+---------------------------+
 *   |         | staged action icon(s)   |   <- top 1/3 (action_attack / action_guard)
 *   |   PRT   | ======= HP =======        |   <- HP bar (green), 1/3 height
 *   |         | ======= MP =======        |   <- MP bar (blue), 1/3 height
 *   +---------+---------------------------+
 *
 *   HP + MP bars together occupy the lower 2/3 of the cell's inner
 *   height; the upper 1/3 is reserved for future ailment / buff icons.
 *   Threat level: right-aligned text on the name row when the player
 *   has an enemy selected in combat.
 *
 * Construct with an [AssetManager] for portrait sprites; set [density]
 * (px / dp) before calling [draw] so layout scales correctly on any screen.
 */
class HeroPanelRenderer(private val assets: AssetManager) {

    /** px per dp. Pulled from the host View's resources. */
    var density: Float = 1f

    private fun dp(v: Float): Float = v * density

    // ----- Paints (colors are inline hex so this file is self-contained) -----

    private val panelBgPaint = Paint().apply { color = Color.parseColor("#FF14110B") }

    private val cellFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF1A1610")
    }
    private val cellBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF8C7A52")
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val portraitEmptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2A241A")
    }
    private val portraitBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5A4F38")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val portraitFillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val portraitInitialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEFE7D0")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val barTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF0F0C08")
    }
    private val barBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5A4F38")
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val hpBarFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3FBF3F")
    }
    private val mpBarFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3A7FE6")
    }

    private val cellLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE6D9B6")
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val cellLabelDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6B6450")
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.ITALIC)
    }
    /**
     * Right-aligned half of the label row: "Threat Level: N".
     * Shares color / size with [cellLabelPaint]; only the alignment
     * differs so the threat readout hugs the cell's right edge.
     */
    private val cellLabelThreatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE6D9B6")
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    /**
     * White "current / max" overlay centred on each HP / MP bar. Drawn
     * after the fill so it's legible over both filled and empty
     * portions. A 1.5 dp black halo underneath keeps the white legible
     * against the bright green / blue fills.
     */
    private val barTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val barTextHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC000000")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    /**
     * Solid white stroke laid over the active hero's cell. Drawn on
     * top of the cell border so the highlight wins the z-order
     * regardless of slot palette.
     */
    private val activeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    /**
     * Paint for blitting animated portrait frames. Bitmap filtering
     * is OFF so the pixel-art portraits stay crisp when scaled to
     * the portrait square; anti-alias is off for the same reason.
     */
    private val portraitBitmapPaint = Paint().apply {
        isAntiAlias = false
        isFilterBitmap = false
    }

    /**
     * Lazy-decoded portrait sprite sets keyed by (class, gender).
     * Each [PortraitSet] holds the idle cycle frames + the hurt
     * frame; we decode on first draw rather than at construction
     * because the player may pick any subset of the 8 (class x
     * gender) combos in a given party.
     */
    private val portraitSets: MutableMap<PortraitKey, PortraitSet> = HashMap()

    private val actionIconBitmapCache: MutableMap<String, Bitmap?> = HashMap()
    private val actionIconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * Per-slot snapshot of the hero whose portrait we're tracking
     * and that hero's last-seen HP. Compared every frame to detect
     * an HP DROP and kick off the hurt-blink animation. Identity
     * (not equality) so swapping in a fresh Hero instance - e.g.
     * starting a new run after load - resets the tracker without
     * spuriously firing the blink.
     */
    private val lastSeenHero: Array<Hero?> = arrayOfNulls(MAX_SLOTS)
    private val lastSeenHp: IntArray = IntArray(MAX_SLOTS) { -1 }

    /**
     * Wall-clock timestamp at which each slot's hurt-blink animation
     * began. `0L` means no animation is in flight; the blink lasts
     * [HURT_BLINK_TOTAL_MS] from this anchor, after which the
     * portrait reverts to the normal idle cycle.
     */
    private val hurtBlinkStartMs: LongArray = LongArray(MAX_SLOTS) { 0L }

    // ----- Public draw entry point -----

    fun draw(canvas: Canvas, width: Int, height: Int, game: Game?) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), panelBgPaint)

        // Text sizes depend on density, set every frame (cheap).
        cellLabelPaint.textSize = dp(13f)
        cellLabelDimPaint.textSize = dp(13f)
        cellLabelThreatPaint.textSize = dp(13f)
        portraitInitialPaint.textSize = dp(22f)
        activeBorderPaint.strokeWidth = dp(2.5f)
        // Bar overlay text + its black halo - keep size synced so the
        // halo sits exactly under the white glyphs.
        val barText = dp(14f)
        barTextPaint.textSize = barText
        barTextHaloPaint.textSize = barText
        barTextHaloPaint.strokeWidth = dp(2f)

        val layout = computeLayout(width, height)
        val party = game?.party
        val heroes = party?.heroes
        // Source of truth for the "you're up" white border. In
        // combat this is whichever hero's turn it currently is
        // (driven by the CombatController); out of combat it's
        // the user-tapped active slot.
        val highlightSlot = game?.spotlightHeroSlot

        // Pre-resolve the hate context so each label row can show
        // "Threat Level: N" without re-deriving (selected enemy,
        // enemy index, hate tracker) from `game` every iteration.
        val hateContext = resolveHateContext(game)

        for (slot in 0..3) {
            drawCellLabel(
                canvas = canvas,
                cell = layout.cells[slot],
                labelY = layout.labelYFor(slot),
                labelH = layout.labelH,
                heroSlot = slot,
                hero = heroes?.getOrNull(slot),
                hateContext = hateContext,
            )
            drawHeroCell(canvas, layout.cells[slot], slot, heroes?.getOrNull(slot), game)
        }

        // Highlight overlay drawn LAST so it sits on top of cell +
        // content. Only draw when the highlighted hero exists in
        // the current party - a stale slot from a prior run would
        // otherwise highlight an empty cell.
        if (highlightSlot != null && heroes?.getOrNull(highlightSlot) != null) {
            drawActiveBorder(canvas, layout.cells[highlightSlot])
        }
    }

    private fun drawActiveBorder(canvas: Canvas, cell: RectF) {
        val radius = dp(6f)
        // Inset by half the stroke width so the rounded rect's outer
        // edge lands exactly on the cell border, not a half pixel out.
        val sw2 = activeBorderPaint.strokeWidth / 2f
        val rect = RectF(cell.left + sw2, cell.top + sw2, cell.right - sw2, cell.bottom - sw2)
        canvas.drawRoundRect(rect, radius, radius, activeBorderPaint)
    }

    /**
     * Returns the slot index (0..3) whose portrait square contains the
     * point ([x], [y]) in panel-local coordinates, or `-1` if none does.
     * Used by [com.tavisdor.app.HeroPanelView] to decide whether a tap
     * should open the hero detail panel.
     */
    fun hitTestPortraitSlot(x: Float, y: Float, width: Int, height: Int): Int {
        val layout = computeLayout(width, height)
        for (slot in 0..3) {
            val portrait = portraitRectIn(layout.cells[slot])
            if (x in portrait.left..portrait.right && y in portrait.top..portrait.bottom) {
                return slot
            }
        }
        return -1
    }

    /**
     * Returns the slot index (0..3) whose full cell rect contains the
     * point ([x], [y]) in panel-local coordinates, or `-1` if the tap
     * landed outside every cell (e.g. on the panel background or the
     * "Name - Class" label band above a cell).
     *
     * Treats the whole hero cell as a hitbox so the player can tap
     * anywhere on a cell - HP bar, name label, blank space inside
     * the cell - and have that hero become the active selection.
     */
    fun hitTestCell(x: Float, y: Float, width: Int, height: Int): Int {
        val layout = computeLayout(width, height)
        for (slot in 0..3) {
            val cell = layout.cells[slot]
            if (x in cell.left..cell.right && y in cell.top..cell.bottom) {
                return slot
            }
        }
        return -1
    }

    /**
     * Mirrors the inset used by [drawHeroCell] (`dp(5f)`) so the hit-test
     * portrait and the drawn portrait are always the same rectangle.
     */
    private fun portraitRectIn(cell: RectF): RectF {
        val pad = dp(5f)
        val innerL = cell.left + pad
        val innerT = cell.top + pad
        val innerB = cell.bottom - pad
        val portraitSize = innerB - innerT
        return RectF(innerL, innerT, innerL + portraitSize, innerB)
    }

    // ----- Layout (shared by draw + hit-test so they stay in lock-step) -----

    /**
     * Right-hand column geometry inside a hero cell: status strip
     * (top 1/3, reserved for buffs / ailments) + HP / MP bars
     * (bottom 2/3, split evenly).
     */
    private data class RightColumnLayout(
        val statusRect: RectF,
        val hpRect: RectF,
        val mpRect: RectF,
    )

    private fun rightColumnLayout(cell: RectF): RightColumnLayout? {
        val pad = dp(5f)
        val innerL = cell.left + pad
        val innerT = cell.top + pad
        val innerR = cell.right - pad
        val innerB = cell.bottom - pad
        val innerH = innerB - innerT
        if (innerH <= 0f) return null

        val portraitSize = innerH
        val rightL = innerL + portraitSize + dp(6f)
        val rightR = innerR
        if (rightR <= rightL) return null

        val statusH = innerH / 3f
        val barsRegionTop = innerT + statusH
        val barsRegionH = innerH * 2f / 3f
        val barGap = dp(4f)
        val barH = (barsRegionH - barGap) / 2f
        if (barH <= 0f) return null

        val hpTop = barsRegionTop
        val hpBottom = hpTop + barH
        val mpTop = hpBottom + barGap
        val mpBottom = mpTop + barH

        return RightColumnLayout(
            statusRect = RectF(rightL, innerT, rightR, innerT + statusH),
            hpRect = RectF(rightL, hpTop, rightR, hpBottom),
            mpRect = RectF(rightL, mpTop, rightR, mpBottom),
        )
    }

    private data class PanelLayout(
        val outerPad: Float,
        val cellHGap: Float,
        val rowVGap: Float,
        val labelH: Float,
        val labelToCellGap: Float,
        val cellW: Float,
        val cellH: Float,
        /** 4 cell rects in slot order: 0,1 = front line; 2,3 = back line. */
        val cells: Array<RectF>,
    ) {
        fun labelYFor(slot: Int): Float = cells[slot].top - labelToCellGap - labelH
    }

    private fun computeLayout(width: Int, height: Int): PanelLayout {
        val outerPad = dp(10f)
        val rowVGap = dp(8f)
        val cellHGap = dp(8f)
        val labelH = dp(16f)
        val labelToCellGap = dp(2f)

        val cellsTotalH = height - outerPad * 2 - rowVGap - labelH * 2 - labelToCellGap * 2
        val cellH = cellsTotalH / 2f
        val cellsTotalW = width - outerPad * 2 - cellHGap
        val cellW = cellsTotalW / 2f

        val frontY = outerPad + labelH + labelToCellGap
        val backY = frontY + cellH + rowVGap + labelH + labelToCellGap

        val leftL = outerPad
        val rightL = outerPad + cellW + cellHGap
        val cells = arrayOf(
            RectF(leftL, frontY, leftL + cellW, frontY + cellH),
            RectF(rightL, frontY, rightL + cellW, frontY + cellH),
            RectF(leftL, backY, leftL + cellW, backY + cellH),
            RectF(rightL, backY, rightL + cellW, backY + cellH),
        )
        return PanelLayout(
            outerPad = outerPad,
            cellHGap = cellHGap,
            rowVGap = rowVGap,
            labelH = labelH,
            labelToCellGap = labelToCellGap,
            cellW = cellW,
            cellH = cellH,
            cells = cells,
        )
    }

    /**
     * Draws a hero's label row above the cell:
     *   - Left:  "Name - Class"
     *   - Right: "Threat Level: N" (combat only, when an enemy is
     *            selected and [hateContext] is non-null)
     *
     * The name is ellipsized when the two strings would overlap so
     * the right-aligned threat readout always wins horizontal space.
     * Renders a dim "(Empty)" italic when no hero is in the slot.
     */
    private fun drawCellLabel(
        canvas: Canvas,
        cell: RectF,
        labelY: Float,
        labelH: Float,
        heroSlot: Int,
        hero: Hero?,
        hateContext: HateRenderContext?,
    ) {
        val baseline = labelBaseline(labelY, labelH, cellLabelPaint)
        if (hero == null) {
            canvas.drawText("(Empty)", cell.left, baseline, cellLabelDimPaint)
            return
        }

        val nameText = "${hero.name} - ${classDisplayName(hero.heroClass)}"
        if (hateContext == null) {
            canvas.drawText(nameText, cell.left, baseline, cellLabelPaint)
            return
        }

        val hateValue = hateContext.hate.hateFor(hateContext.enemyIdx, heroSlot)
        val threatText = "Threat Level: $hateValue"
        canvas.drawText(threatText, cell.right, baseline, cellLabelThreatPaint)

        val threatW = cellLabelThreatPaint.measureText(threatText)
        val maxNameW = (cell.width() - threatW - dp(4f)).coerceAtLeast(0f)
        val displayName = TextUtils.ellipsize(
            nameText,
            TextPaint(cellLabelPaint),
            maxNameW,
            TextUtils.TruncateAt.END,
        ).toString()
        canvas.drawText(displayName, cell.left, baseline, cellLabelPaint)
    }

    /** Shared vertical centering for the label band above each hero cell. */
    private fun labelBaseline(labelY: Float, labelH: Float, paint: Paint): Float {
        return labelY + labelH - (paint.descent() + paint.ascent()) / 2f - labelH / 2f
    }

    private fun classDisplayName(cls: HeroClass): String =
        cls.name.lowercase().replaceFirstChar { it.uppercaseChar() }

    // ----- Per-hero cell -----

    private fun drawHeroCell(
        canvas: Canvas,
        cell: RectF,
        heroSlot: Int,
        hero: Hero?,
        game: Game?,
    ) {
        val cellRadius = dp(6f)
        canvas.drawRoundRect(cell, cellRadius, cellRadius, cellFillPaint)
        canvas.drawRoundRect(cell, cellRadius, cellRadius, cellBorderPaint)

        val pad = dp(5f)
        val innerL = cell.left + pad
        val innerT = cell.top + pad
        val innerR = cell.right - pad
        val innerB = cell.bottom - pad
        val innerH = innerB - innerT

        // Portrait: square, full inner height, anchored left.
        val portraitSize = innerH
        val portrait = RectF(innerL, innerT, innerL + portraitSize, innerB)
        drawPortrait(canvas, portrait, heroSlot, hero)

        val column = rightColumnLayout(cell) ?: return
        drawStagedActionIcons(canvas, heroSlot, column.statusRect, game)
        drawBar(
            canvas = canvas,
            rect = column.hpRect,
            value = hero?.hp,
            max = hero?.maxHp,
            fillPaint = hpBarFillPaint,
        )
        drawBar(
            canvas = canvas,
            rect = column.mpRect,
            value = hero?.mp,
            max = hero?.maxMp,
            fillPaint = mpBarFillPaint,
        )
    }

    private fun ratio(value: Int?, max: Int?): Float {
        if (value == null || max == null || max <= 0) return 0f
        return (value.toFloat() / max).coerceIn(0f, 1f)
    }

    // ----- Portrait -----

    /**
     * Renders the portrait square for [hero] inside [rect]. Three
     * distinct paths:
     *   1. Empty slot      -> the original "(empty)" rounded square.
     *   2. Hero with art   -> animated idle cycle, periodically
     *                         interrupted by a hurt-blink whenever
     *                         [hero]'s HP just dropped.
     *   3. Hero w/o art    -> the legacy color-tint + first-letter
     *                         fallback, so a missing sprite asset
     *                         degrades gracefully.
     *
     * Hurt detection lives in [updateHurtTracker]; this method only
     * picks which frame to draw on top of the colored background.
     */
    private fun drawPortrait(canvas: Canvas, rect: RectF, slot: Int, hero: Hero?) {
        val r = dp(4f)
        if (hero == null) {
            // Empty slot also clears the per-slot HP tracker so we
            // don't fire a phantom blink the moment a new party
            // fills the cell.
            lastSeenHero[slot] = null
            lastSeenHp[slot] = -1
            hurtBlinkStartMs[slot] = 0L
            canvas.drawRoundRect(rect, r, r, portraitEmptyPaint)
            canvas.drawRoundRect(rect, r, r, portraitBorderPaint)
            return
        }

        // Refresh hp tracker BEFORE choosing the frame so an HP
        // drop on this exact tick is already reflected in the
        // blink decision.
        updateHurtTracker(slot, hero)

        // Background colored panel + border are drawn under the
        // sprite so a sprite that's smaller than the portrait
        // square still reads as the right class color.
        portraitFillPaint.color = portraitColorFor(hero.heroClass)
        canvas.drawRoundRect(rect, r, r, portraitFillPaint)

        val set = portraitSetFor(hero.heroClass, hero.gender)
        val frame = chooseFrame(slot, set)
        if (frame != null) {
            canvas.drawBitmap(frame.bitmap, frame.src, rect, portraitBitmapPaint)
        } else {
            // Sprite decode failed - fall back to the legacy initial-glyph
            // placeholder so the slot is still identifiable.
            val initial = hero.heroClass.name.first().toString()
            val cx = (rect.left + rect.right) / 2f
            val cy = (rect.top + rect.bottom) / 2f -
                (portraitInitialPaint.descent() + portraitInitialPaint.ascent()) / 2f
            canvas.drawText(initial, cx, cy, portraitInitialPaint)
        }

        // Border painted LAST so it sits on top of both the sprite
        // and any colored padding the sprite didn't cover.
        canvas.drawRoundRect(rect, r, r, portraitBorderPaint)
    }

    /**
     * Compares [hero]'s current HP to what we drew last frame and
     * arms the hurt-blink timer whenever HP dropped. Three rules:
     *   - Different Hero instance in this slot than last frame
     *     (load / new run / KO-revive) -> snapshot HP, no blink.
     *   - HP went DOWN -> snapshot the lower value, start blink.
     *   - HP went UP / unchanged       -> snapshot, no blink (heals
     *     and steady-state frames both pass through silently).
     */
    private fun updateHurtTracker(slot: Int, hero: Hero) {
        val prev = lastSeenHero[slot]
        val prevHp = lastSeenHp[slot]
        if (prev !== hero) {
            lastSeenHero[slot] = hero
            lastSeenHp[slot] = hero.hp
            hurtBlinkStartMs[slot] = 0L
            return
        }
        if (hero.hp < prevHp) {
            hurtBlinkStartMs[slot] = SystemClock.uptimeMillis()
        }
        lastSeenHp[slot] = hero.hp
    }

    /**
     * Picks the bitmap+src-rect to blit for [slot]'s current frame.
     * Returns null when even the idle cycle has no decoded frames
     * (e.g. all assets failed to load); the caller then falls back
     * to the colored-initial placeholder.
     *
     * Frame selection rules:
     *   - Inside the hurt-blink window: alternate between the hurt
     *     sprite and the current idle frame every
     *     [HURT_BLINK_FLASH_MS] ms. With [HURT_BLINK_COUNT] = 3
     *     and the alternating cadence, the player sees the hurt
     *     sprite flash 3 times before the cycle resumes cleanly.
     *   - Otherwise: pick the idle frame for this slot based on
     *     wall-clock time, using a tiny per-slot phase so adjacent
     *     heroes don't bob in lock-step.
     */
    private fun chooseFrame(slot: Int, set: PortraitSet): PortraitFrame? {
        val now = SystemClock.uptimeMillis()
        val cycle = set.cycle
        val idleFrame = if (cycle.isNotEmpty()) {
            val phase = slot * (CYCLE_FRAME_MS / MAX_SLOTS)
            val idx = ((now + phase) / CYCLE_FRAME_MS).toInt().mod(cycle.size)
            cycle[idx]
        } else null

        val blinkStart = hurtBlinkStartMs[slot]
        if (blinkStart > 0L) {
            val elapsed = now - blinkStart
            if (elapsed < HURT_BLINK_TOTAL_MS) {
                val flashIdx = (elapsed / HURT_BLINK_FLASH_MS).toInt()
                // Even flashes show the hurt sprite; odd flashes
                // show the normal cycle, so the eye sees a clean
                // "ouch!" flicker.
                val showHurt = flashIdx % 2 == 0
                if (showHurt && set.hurt != null) return set.hurt
                if (idleFrame != null) return idleFrame
                if (set.hurt != null) return set.hurt
            } else {
                hurtBlinkStartMs[slot] = 0L
            }
        }
        return idleFrame
    }

    /**
     * Decodes and caches the [PortraitSet] for the (class, gender)
     * pair. Decoded on first access then served from [portraitSets]
     * - keeps the cold-start cost low when the party only uses 4
     * of the 8 possible portrait sets.
     */
    private fun portraitSetFor(cls: HeroClass, gender: Gender): PortraitSet {
        val key = PortraitKey(cls, gender)
        return portraitSets.getOrPut(key) { loadPortraitSet(cls, gender) }
    }

    private fun loadPortraitSet(cls: HeroClass, gender: Gender): PortraitSet {
        val spec = PortraitCatalog.specFor(cls, gender)
        val cycle = spec.cycleAssets.mapNotNull { path ->
            val bmp = tryLoadBitmap(assets, path) ?: return@mapNotNull null
            PortraitFrame(bmp, Rect(0, 0, bmp.width, bmp.height))
        }
        val hurt = spec.hurtAsset
            .let { tryLoadBitmap(assets, it) }
            ?.let { bmp -> PortraitFrame(bmp, Rect(0, 0, bmp.width, bmp.height)) }
        return PortraitSet(cycle = cycle, hurt = hurt)
    }

    private fun portraitColorFor(cls: HeroClass): Int = when (cls) {
        HeroClass.MAGE -> Color.parseColor("#FF5A3C8C")
        HeroClass.THIEF -> Color.parseColor("#FF326E50")
        HeroClass.FIGHTER -> Color.parseColor("#FF963232")
        HeroClass.ARCHER -> Color.parseColor("#FFA08232")
    }

    /**
     * Snapshot of "which enemy's hate are we displaying right now,
     * and where do we look up the numbers?" - resolved once per
     * draw call so the per-cell hate loops don't have to repeat
     * the (game -> combat -> enemy index) lookup four times.
     *
     * Returns null whenever there's nothing to display (no combat,
     * no selection, selected enemy dead, etc.); the renderer then
     * leaves every hate slot empty.
     */
    private data class HateRenderContext(
        val enemyIdx: Int,
        val hate: HateTracker,
    )

    private fun resolveHateContext(game: Game?): HateRenderContext? {
        val combat = game?.combat ?: return null
        val selected: Enemy = game.selectedEnemy ?: return null
        if (!selected.isAlive) return null
        val enemyIdx = combat.enemies.indexOf(selected)
        if (enemyIdx < 0) return null
        return HateRenderContext(enemyIdx = enemyIdx, hate = combat.hate)
    }

    // ----- Staged action icons -----

    /**
     * Draws `action_attack` / `action_guard` in the status strip above the
     * HP bar, left-aligned with the bars. Shown after the player stages
     * skills in the assignment panel.
     */
    private fun drawStagedActionIcons(
        canvas: Canvas,
        heroSlot: Int,
        statusRect: RectF,
        game: Game?,
    ) {
        val g = game ?: return
        val icons = ArrayList<String>(3)
        if (g.isHeroWaiting(heroSlot)) icons += WAIT_ICON_ASSET
        for (skill in g.stagedSkillsForPanel(heroSlot)) {
            actionIconAssetFor(skill)?.let { icons += it }
        }
        if (icons.isEmpty()) return

        val maxH = statusRect.height()
        if (maxH <= 0f) return

        val gap = dp(4f)
        val count = icons.size
        val slotW = (statusRect.width() - gap * (count - 1).coerceAtLeast(0)) / count
        val iconSize = min(maxH, slotW)
        if (iconSize <= 0f) return

        var x = statusRect.left
        val y = statusRect.top + (maxH - iconSize) / 2f
        val dst = RectF()

        for (asset in icons) {
            val bmp = loadActionIcon(asset) ?: continue
            dst.set(x, y, x + iconSize, y + iconSize)
            canvas.drawBitmap(bmp, actionIconSrcRect(bmp), dst, actionIconPaint)
            x += iconSize + gap
        }
    }

    private fun actionIconAssetFor(skill: Skill): String? = when (skill.button) {
        SkillButton.ACTION -> ACTION_ATTACK_ASSET
        SkillButton.GUARD -> ACTION_GUARD_ASSET
    }

    private fun loadActionIcon(asset: String): Bitmap? =
        actionIconBitmapCache.getOrPut(asset) {
            tryLoadBitmap(assets, "sprites/$asset.png")
        }

    private fun actionIconSrcRect(bmp: Bitmap): Rect =
        Rect(0, 0, bmp.width, bmp.height)

    // ----- Bars (HP / MP) -----

    /**
     * Draws one bar. The fill width is derived from [value] / [max];
     * after the fill + border we paint a centred "current/max" white
     * label on top so the player can read exact numbers at a glance.
     * Empty slots (`value == null` or `max == null`) skip the label
     * and render an empty track.
     */
    private fun drawBar(
        canvas: Canvas,
        rect: RectF,
        value: Int?,
        max: Int?,
        fillPaint: Paint,
    ) {
        val r = dp(2f)
        val fillRatio = ratio(value, max)

        canvas.drawRoundRect(rect, r, r, barTrackPaint)
        if (fillRatio > 0f) {
            val fillRect = RectF(
                rect.left,
                rect.top,
                rect.left + rect.width() * fillRatio,
                rect.bottom,
            )
            canvas.drawRoundRect(fillRect, r, r, fillPaint)
        }
        canvas.drawRoundRect(rect, r, r, barBorderPaint)

        // Centered "current/max" overlay. Skip when the slot is empty
        // (no hero in this cell) - otherwise we'd render "null/null".
        if (value != null && max != null) {
            val label = "$value/$max"
            val cx = (rect.left + rect.right) / 2f
            // Vertical centring: the y argument to drawText is the
            // text baseline; subtract half the ascent+descent so the
            // glyph box is centred on the bar's midline.
            val cy = (rect.top + rect.bottom) / 2f -
                (barTextPaint.descent() + barTextPaint.ascent()) / 2f
            canvas.drawText(label, cx, cy, barTextHaloPaint)
            canvas.drawText(label, cx, cy, barTextPaint)
        }
    }

    /**
     * Cached source rect + bitmap pair so the per-frame blit
     * doesn't allocate a new [Rect] every time we draw. Created
     * once per decoded sprite in [loadPortraitSet].
     */
    private data class PortraitFrame(val bitmap: Bitmap, val src: Rect)

    /**
     * Holds the decoded idle cycle + hurt sprite for a single
     * (class, gender) portrait set. [cycle] may be empty if every
     * asset failed to decode; the renderer falls back to the
     * initial-glyph placeholder in that case.
     */
    private data class PortraitSet(val cycle: List<PortraitFrame>, val hurt: PortraitFrame?)

    /** Map key for [portraitSets]; pure value type, safe to use as a HashMap key. */
    private data class PortraitKey(val cls: HeroClass, val gender: Gender)

    companion object {
        private const val TAG = "HeroPanelRenderer"

        private const val ACTION_ATTACK_ASSET = "action_attack"
        private const val WAIT_ICON_ASSET = "wait"
        private const val ACTION_GUARD_ASSET = "action_guard"

        /** Always 4 hero slots; cached so the per-slot state arrays stay sized correctly. */
        private const val MAX_SLOTS: Int = 4

        /**
         * Milliseconds each idle portrait frame stays on screen
         * before the cycle advances. 625ms gives a ~1.25s loop for the
         * 2-frame fighter / thief / archer and ~2.5s for the 4-frame mage.
         */
        private const val CYCLE_FRAME_MS: Long = 625L

        /**
         * Milliseconds between hurt-blink flashes (one hurt frame +
         * one idle frame = two consecutive flash slots). Combined
         * with [HURT_BLINK_COUNT] this controls the "ouch" pacing:
         * 3 blinks x (125ms hurt + 125ms idle) = 750ms total.
         */
        private const val HURT_BLINK_FLASH_MS: Long = 125L

        /** Number of times the hurt sprite flashes before the cycle resumes. */
        private const val HURT_BLINK_COUNT: Int = 3

        /**
         * Total length of the hurt-blink animation window. After
         * this elapses [hurtBlinkStartMs] resets to 0L and the
         * portrait reverts to its normal cycle until the next
         * HP drop.
         */
        private const val HURT_BLINK_TOTAL_MS: Long = HURT_BLINK_FLASH_MS * HURT_BLINK_COUNT * 2L

        /**
         * Asset-decode helper. Mirrors the one in [DungeonRenderer] -
         * intentionally duplicated rather than shared so the two
         * renderers can be reasoned about independently. Returns
         * null + logs a warning when the asset is missing; the
         * caller falls back to drawing the slot empty.
         */
        private fun tryLoadBitmap(assets: AssetManager, path: String): Bitmap? {
            return runCatching {
                assets.open(path).use { BitmapFactory.decodeStream(it) }
            }.onFailure {
                Log.w(TAG, "Failed to load sprite $path: ${it.message}")
            }.getOrNull()
        }
    }
}
