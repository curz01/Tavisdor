package com.tavisdor.app.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import com.tavisdor.app.game.Game
import com.tavisdor.app.party.Hero
import com.tavisdor.app.party.HeroClass
import com.tavisdor.app.skills.SkillButton

/**
 * Draws the persistent bottom 2x2 hero panel inside
 * [com.tavisdor.app.HeroPanelView]. Top row = front line (slots 0, 1),
 * bottom row = back line (slots 2, 3).
 *
 * Each hero cell uses this layout:
 *
 *   +---------+--------------------------+
 *   |         | [ACT] [GRD] [SPL]        |   <- action buttons (yellow)
 *   |   PRT   | ======= HP =======       |   <- HP bar (green)
 *   |         | ======= MP =======       |   <- MP bar (blue)
 *   +---------+--------------------------+
 *
 * Set [density] (px / dp) before calling [draw] so layout scales correctly on
 * any screen.
 */
class HeroPanelRenderer {

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

    private val buttonFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFE6C12C")
    }
    private val buttonEmptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3A3220")
    }
    private val buttonBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF5A4F38")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val buttonLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF2A1F10")
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

    // ----- Public draw entry point -----

    fun draw(canvas: Canvas, width: Int, height: Int, game: Game?) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), panelBgPaint)

        // Text sizes depend on density, set every frame (cheap).
        cellLabelPaint.textSize = dp(11f)
        cellLabelDimPaint.textSize = dp(11f)
        portraitInitialPaint.textSize = dp(22f)
        buttonLabelPaint.textSize = dp(9f)
        activeBorderPaint.strokeWidth = dp(2.5f)
        // Bar overlay text + its black halo - keep size synced so the
        // halo sits exactly under the white glyphs.
        val barText = dp(10f)
        barTextPaint.textSize = barText
        barTextHaloPaint.textSize = barText
        barTextHaloPaint.strokeWidth = dp(1.5f)

        val layout = computeLayout(width, height)
        val party = game?.party
        val heroes = party?.heroes
        val activeSlot = game?.activeHeroSlot

        for (slot in 0..3) {
            drawCellLabel(canvas, layout.labelXFor(slot), layout.labelYFor(slot), layout.labelH, heroes?.getOrNull(slot))
            drawHeroCell(canvas, layout.cells[slot], heroes?.getOrNull(slot))
        }

        // Active-hero overlay drawn LAST so it sits on top of cell +
        // content. Only draw when the active hero exists in the
        // current party - a stale activeSlot from a prior run would
        // otherwise highlight an empty cell.
        if (activeSlot != null && heroes?.getOrNull(activeSlot) != null) {
            drawActiveBorder(canvas, layout.cells[activeSlot])
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

    /**
     * Result of [hitTestActionButton]: which hero slot's button was tapped
     * and which of the three buttons (ACT / GRD / SPL).
     */
    data class ActionButtonHit(val slot: Int, val button: SkillButton)

    /**
     * Returns the (slot, button) the point ([x], [y]) lands on for the
     * 3-button strip in a hero cell, or null if the tap missed every
     * button. Used by [com.tavisdor.app.HeroPanelView] to surface the
     * ACT / GRD / SPL popup with the right hero's skill list.
     */
    fun hitTestActionButton(x: Float, y: Float, width: Int, height: Int): ActionButtonHit? {
        val layout = computeLayout(width, height)
        for (slot in 0..3) {
            val buttons = actionButtonRectsIn(layout.cells[slot])
            buttons.forEachIndexed { i, rect ->
                if (x in rect.left..rect.right && y in rect.top..rect.bottom) {
                    return ActionButtonHit(slot, BUTTON_ORDER[i])
                }
            }
        }
        return null
    }

    /**
     * Geometry of the 3 ACT / GRD / SPL buttons inside a hero cell.
     * Mirrors the math in [drawHeroCell] + [drawButtonRow] so the
     * hit-test stays in lock-step with the drawn buttons.
     */
    private fun actionButtonRectsIn(cell: RectF): Array<RectF> {
        val pad = dp(5f)
        val innerL = cell.left + pad
        val innerT = cell.top + pad
        val innerR = cell.right - pad
        val innerB = cell.bottom - pad
        val innerH = innerB - innerT
        if (innerH <= 0f) return emptyArray()

        val portraitSize = innerH
        val rightL = innerL + portraitSize + dp(6f)
        val rightR = innerR
        val rowW = rightR - rightL
        if (rowW <= 0f) return emptyArray()

        val buttonRowH = innerH * 0.48f
        val gapV = innerH * 0.06f
        val barH = innerH * 0.17f
        val totalContentH = buttonRowH + gapV * 2 + barH * 2
        val topSlack = (innerH - totalContentH) / 2f
        val btnRowTop = innerT + topSlack

        val btnGap = dp(5f)
        val widthLimited = (rowW - btnGap * 2f) / 3f
        val btnSize = minOf(widthLimited, buttonRowH)
        if (btnSize <= 0f) return emptyArray()
        val stripW = btnSize * 3f + btnGap * 2f
        val startX = rightL + (rowW - stripW) / 2f
        val startY = btnRowTop + (buttonRowH - btnSize) / 2f

        return Array(3) { i ->
            val l = startX + i * (btnSize + btnGap)
            RectF(l, startY, l + btnSize, startY + btnSize)
        }
    }

    /** Index -> button: ACT (0), GRD (1), SPL (2). Matches [buttonLabels]. */
    private val BUTTON_ORDER = arrayOf(
        SkillButton.ACTION,
        SkillButton.GUARD,
        SkillButton.SPELLS,
    )

    // ----- Layout (shared by draw + hit-test so they stay in lock-step) -----

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
        fun labelXFor(slot: Int): Float = cells[slot].left
        fun labelYFor(slot: Int): Float = cells[slot].top - labelToCellGap - labelH
    }

    private fun computeLayout(width: Int, height: Int): PanelLayout {
        val outerPad = dp(10f)
        val rowVGap = dp(8f)
        val cellHGap = dp(8f)
        val labelH = dp(14f)
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
     * Draws a hero's "Name - Class" header above the cell. Renders a dim
     * "(Empty)" italic when no hero is in the slot.
     */
    private fun drawCellLabel(canvas: Canvas, x: Float, y: Float, labelH: Float, hero: Hero?) {
        val paint = if (hero != null) cellLabelPaint else cellLabelDimPaint
        val text = if (hero != null) {
            "${hero.name} - ${classDisplayName(hero.heroClass)}"
        } else {
            "(Empty)"
        }
        // Center text vertically inside the label band.
        val baseline = y + labelH - (paint.descent() + paint.ascent()) / 2f - labelH / 2f
        canvas.drawText(text, x, baseline, paint)
    }

    private fun classDisplayName(cls: HeroClass): String =
        cls.name.lowercase().replaceFirstChar { it.uppercaseChar() }

    // ----- Per-hero cell -----

    private fun drawHeroCell(canvas: Canvas, cell: RectF, hero: Hero?) {
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
        drawPortrait(canvas, portrait, hero)

        // Right column: starts after portrait + an inner gap, runs to right edge.
        val rightL = portrait.right + dp(6f)
        val rightR = innerR
        if (rightR <= rightL) return // cell too narrow, skip details

        // Vertical split of the right column:
        //   buttonRowH : ~48% of innerH
        //   gap        : ~6%
        //   hpBarH     : ~17%
        //   gap        : ~6%
        //   mpBarH     : ~17%   (= 94%, the remaining 6% is even top/bottom slack)
        val buttonRowH = innerH * 0.48f
        val gapV = innerH * 0.06f
        val barH = innerH * 0.17f
        val totalContentH = buttonRowH + gapV * 2 + barH * 2
        val topSlack = (innerH - totalContentH) / 2f

        val btnRowTop = innerT + topSlack
        val btnRowBottom = btnRowTop + buttonRowH
        val hpTop = btnRowBottom + gapV
        val hpBottom = hpTop + barH
        val mpTop = hpBottom + gapV
        val mpBottom = mpTop + barH

        drawButtonRow(canvas, RectF(rightL, btnRowTop, rightR, btnRowBottom), hero)
        drawBar(
            canvas = canvas,
            rect = RectF(rightL, hpTop, rightR, hpBottom),
            value = hero?.hp,
            max = hero?.maxHp,
            fillPaint = hpBarFillPaint,
        )
        drawBar(
            canvas = canvas,
            rect = RectF(rightL, mpTop, rightR, mpBottom),
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

    private fun drawPortrait(canvas: Canvas, rect: RectF, hero: Hero?) {
        val r = dp(4f)
        if (hero == null) {
            canvas.drawRoundRect(rect, r, r, portraitEmptyPaint)
            canvas.drawRoundRect(rect, r, r, portraitBorderPaint)
            return
        }
        portraitFillPaint.color = portraitColorFor(hero.heroClass)
        canvas.drawRoundRect(rect, r, r, portraitFillPaint)
        canvas.drawRoundRect(rect, r, r, portraitBorderPaint)

        // First letter of class as a placeholder until real portrait sprites land.
        val initial = hero.heroClass.name.first().toString()
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f -
            (portraitInitialPaint.descent() + portraitInitialPaint.ascent()) / 2f
        canvas.drawText(initial, cx, cy, portraitInitialPaint)
    }

    private fun portraitColorFor(cls: HeroClass): Int = when (cls) {
        HeroClass.MAGE -> Color.parseColor("#FF5A3C8C")
        HeroClass.THIEF -> Color.parseColor("#FF326E50")
        HeroClass.FIGHTER -> Color.parseColor("#FF963232")
        HeroClass.ARCHER -> Color.parseColor("#FFA08232")
    }

    // ----- Button row (ACT / GRD / SPL) -----

    private val buttonLabels = arrayOf("ACT", "GRD", "SPL")

    private fun drawButtonRow(canvas: Canvas, row: RectF, hero: Hero?) {
        val rowW = row.width()
        val rowH = row.height()
        if (rowW <= 0f || rowH <= 0f) return

        val btnGap = dp(5f)
        // Buttons are squares. Width-limit (3 squares + 2 gaps) vs height-limit.
        val widthLimited = (rowW - btnGap * 2f) / 3f
        val btnSize = minOf(widthLimited, rowH)
        if (btnSize <= 0f) return

        // Center the 3-button strip both horizontally and vertically in the row.
        val stripW = btnSize * 3f + btnGap * 2f
        val startX = row.left + (rowW - stripW) / 2f
        val startY = row.top + (rowH - btnSize) / 2f

        val labelOffsetY = -(buttonLabelPaint.descent() + buttonLabelPaint.ascent()) / 2f
        val radius = dp(3f)
        for (i in 0..2) {
            val l = startX + i * (btnSize + btnGap)
            val btn = RectF(l, startY, l + btnSize, startY + btnSize)
            val fill = if (hero != null) buttonFillPaint else buttonEmptyPaint
            canvas.drawRoundRect(btn, radius, radius, fill)
            canvas.drawRoundRect(btn, radius, radius, buttonBorderPaint)
            if (hero != null) {
                val cx = (btn.left + btn.right) / 2f
                val cy = (btn.top + btn.bottom) / 2f + labelOffsetY
                canvas.drawText(buttonLabels[i], cx, cy, buttonLabelPaint)
            }
        }
    }

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
}
