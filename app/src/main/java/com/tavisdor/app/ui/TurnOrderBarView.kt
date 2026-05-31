package com.tavisdor.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.tavisdor.app.combat.Combat
import com.tavisdor.app.combat.CombatRound
import com.tavisdor.app.combat.InitiativeEntry
import com.tavisdor.app.enemies.Enemy
import com.tavisdor.app.party.Hero
import com.tavisdor.app.party.HeroClass
import com.tavisdor.app.render.PortraitCatalog

/**
 * Top-of-screen strip showing the active [Combat]'s turn order
 * left-to-right. Leftmost portrait = goes first this round.
 *
 * Render rules:
 *   - Each surviving [InitiativeEntry] gets one square portrait
 *     slot. Entries in [CombatRound.removedEntries] are filtered
 *     out; survivors after a removal shift left to close the gap.
 *   - Hero entries pull a color + initial from [HeroClass]; enemy
 *     entries draw their template's portrait sprite if it loads,
 *     otherwise a red-tinted square + name's first letter.
 *   - The leftmost still-visible portrait is the "currently acting"
 *     slot and gets a thin amber outline.
 *   - [CombatRound.Leaver.Kind.TURN_ENDED] slides left + fades; the
 *     gap stays put until the round resets.
 *   - [CombatRound.Leaver.Kind.DEFEATED] slides DOWN + fades. When
 *     the animation completes the entry is permanently removed;
 *     surviving portraits then interpolate their X across the
 *     [CombatRound.shiftProgress] timeline (driven by
 *     [CombatRound.shiftFromSlots]) to close the gap.
 *   - When the round wraps, all portraits are re-shown at full
 *     alpha. Removed entries stay gone.
 *
 * The view is a pure reader of [CombatRound]; the
 * [com.tavisdor.app.combat.CombatController] advances the round
 * state against the game-loop delta. Call [setCombat] from the
 * activity when combat begins / ends.
 */
class TurnOrderBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    // ----- External state -----

    private var combat: Combat? = null

    // ----- Paints -----

    private val portraitBgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val portraitBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_PORTRAIT_BORDER
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
    }
    private val portraitActiveBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_ACTIVE_BORDER
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }
    private val portraitLetterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFF5ECD2")
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textSize = dp(20f)
    }
    private val backdropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_BACKDROP
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    // ----- Sprite cache for both hero and enemy portraits -----
    //
    // Keyed by asset path so a single decode is shared regardless
    // of whether the entry is a hero (using PortraitCatalog's
    // first cycle frame) or an enemy (template.portraitAsset).
    // null values are cached deliberately to avoid retrying the
    // decode every frame when the file is missing.
    private val portraitCache: MutableMap<String, Bitmap?> = HashMap()

    // ----- Reusable rects to avoid per-frame alloc -----

    private val tmpSlotRect = RectF()
    private val tmpSrcRect = Rect()

    // ----- Hit-test cache populated each draw -----
    //
    // Each entry is the [InitiativeEntry] currently rendered plus
    // the rect it lives in. onTouchEvent walks this list to map a
    // tap (x, y) back to the entry that was hit. We rebuild it
    // every draw so the rects always match what's on screen,
    // including in-flight slide / shift animations.
    //
    // The pooled RectF instances are reused across frames to keep
    // allocation off the per-frame hot path; the list grows lazily
    // up to the largest initiative seen so far.
    private val hitRectPool: MutableList<RectF> = mutableListOf()
    private val hitEntries: MutableList<InitiativeEntry> = mutableListOf()
    private var hitCount: Int = 0

    // ----- Public hooks called by the activity / game loop -----

    /**
     * Fires when the player taps a portrait in the strip. The
     * argument is the [InitiativeEntry] whose slot was hit; the
     * activity branches on [InitiativeEntry.kind] to either
     * select the hero (white spotlight border) or center the
     * camera on the enemy. Defeated / mid-leaver portraits are
     * intentionally NOT tappable - the cache only includes
     * entries the player can act on.
     */
    var onPortraitTapped: ((InitiativeEntry) -> Unit)? = null

    // ----- Touch state -----

    private var downX: Float = 0f
    private var downY: Float = 0f
    private var tracking: Boolean = false

    /**
     * Bind the combat whose turn order this view should display.
     * Pass null to clear (and hide via visibility on the parent).
     */
    fun setCombat(combat: Combat?) {
        this.combat = combat
        invalidate()
    }

    // ----- Drawing -----

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val c = combat
        if (c == null || c.initiative.isEmpty()) {
            // Nothing to draw; the strip is still visible, but
            // empty - parent controls visibility on COMBAT mode.
            return
        }

        // The CombatController advances `c.round` against the
        // game-loop delta; this view is a pure reader. If any
        // leaver / shift animation is in flight, keep posting
        // invalidates so we sample the new progress each frame.
        drawBackdrop(canvas)
        drawPortraits(canvas, c)

        if (c.round.isAnimating) postInvalidateOnAnimation()
    }

    private fun drawBackdrop(canvas: Canvas) {
        canvas.drawRect(
            0f, 0f, width.toFloat(), height.toFloat(),
            backdropPaint,
        )
    }

    private fun drawPortraits(canvas: Canvas, combat: Combat) {
        // Reset the hit-test cache up front so a frame that ends
        // up rendering no portraits (e.g. round just resolved)
        // leaves an empty hit set.
        hitCount = 0

        val slot = portraitSlotSize().coerceAtLeast(1f)
        val gap = dp(SLOT_GAP_DP)
        val topMargin = (height - slot) / 2f
        val round = combat.round
        val stride = slot + gap

        // ----- First pass: compute the visible group's settled
        //       footprint so we can center it horizontally in the
        //       strip.
        //
        // We use the SETTLED slot index (renderedSlot before any
        // lerp / leaver translation) so the group's center stays
        // put while a leaver slides off or a survivor shifts to
        // close a defeat gap - portraits slide into a stable
        // layout instead of the layout sliding under them. Once
        // a slot is permanently past (acted + no animation) or
        // removed, it drops out of the footprint on the next
        // frame and the group recenters into its new bounds.
        var minSlot = Int.MAX_VALUE
        var maxSlot = Int.MIN_VALUE
        for (initIdx in combat.round.roundQueue) {
            val entry = combat.initiative[initIdx]
            if (entry in round.removedEntries) continue
            val midAnim = round.leavers.any { it.entry === entry } ||
                (round.waitReinsert?.entry === entry)
            if (isSlotPast(initIdx, round) && !midAnim) continue
            val s = displayedSlotFor(entry, round).coerceAtLeast(0)
            if (s < minSlot) minSlot = s
            if (s > maxSlot) maxSlot = s
        }
        if (minSlot == Int.MAX_VALUE) return
        val groupWidth = (maxSlot - minSlot) * stride + slot
        // leftMargin is the X coordinate that displayedSlotOf == 0
        // would land at. By offsetting from the centered group
        // anchor we avoid having to thread the centering math into
        // every per-portrait shift / leaver formula below; they
        // all keep using `leftMargin + renderedSlot * stride`.
        val leftMargin = (width - groupWidth) / 2f - minSlot * stride

        for (initIdx in combat.round.roundQueue) {
            val entry = combat.initiative[initIdx]
            if (entry in round.removedEntries) continue

            // Find any active animation on this entry. The hero
            // that just killed an enemy can be carrying a
            // TURN_ENDED leaver while the enemy carries a
            // DEFEATED leaver in the same frame, so both lookups
            // happen independently.
            val turnEndLeaver = round.leavers.firstOrNull {
                it.entry === entry && it.kind == CombatRound.Leaver.Kind.TURN_ENDED
            }
            val defeatLeaver = round.leavers.firstOrNull {
                it.entry === entry && it.kind == CombatRound.Leaver.Kind.DEFEATED
            }

            // "Past-acted this round" check. Hidden unless the
            // slot has an in-flight TURN_ENDED OR DEFEATED leaver
            // we still need to animate to completion.
            val waitReinsert = round.waitReinsert
            val midAnim = turnEndLeaver != null || defeatLeaver != null ||
                waitReinsert?.entry === entry
            if (isSlotPast(initIdx, round) && !midAnim) {
                continue
            }

            val currentSlot = round.displayedSlotOf(entry).coerceAtLeast(0)
            val fromSlot = round.shiftFromSlots[entry]?.toFloat() ?: currentSlot.toFloat()
            var renderedSlot = if (round.shiftProgress < 1f && waitReinsert?.entry !== entry) {
                lerp(fromSlot, currentSlot.toFloat(), round.shiftProgress)
            } else {
                currentSlot.toFloat()
            }
            if (waitReinsert != null && waitReinsert.entry === entry && turnEndLeaver == null) {
                val p = waitReinsert.progress.coerceIn(0f, 1f)
                val target = waitReinsert.toDisplayedSlot.toFloat()
                renderedSlot = lerp(-WAIT_OFFSCREEN_SLOTS, target, p)
            }
            val baseLeft = leftMargin + renderedSlot * stride
            val baseTop = topMargin

            // Apply per-leaver transforms. Both kinds can coexist
            // on the same entry; effects compose multiplicatively
            // for alpha and additively for translation.
            var alpha = 1f
            var translateX = 0f
            var translateY = 0f
            if (turnEndLeaver != null) {
                val p = turnEndLeaver.progress.coerceIn(0f, 1f)
                alpha *= (1f - p)
                translateX += -p * stride * SLIDE_SLOTS_FACTOR
            }
            if (defeatLeaver != null) {
                val p = defeatLeaver.progress.coerceIn(0f, 1f)
                alpha *= (1f - p)
                translateY += p * slot * DEFEAT_DROP_FACTOR
            }

            tmpSlotRect.set(
                baseLeft + translateX,
                baseTop + translateY,
                baseLeft + slot + translateX,
                baseTop + slot + translateY,
            )

            // Record this slot for hit testing. We exclude
            // entries with a leaver in flight - a portrait
            // sliding off / fading out shouldn't be tappable
            // because the entry is either past-acted (the action
            // already resolved) or about to be removed (corpse).
            // The settled portraits remain pickable through any
            // shift animation, since the shift just relocates a
            // slot whose owner is still a valid target.
            if (turnEndLeaver == null && defeatLeaver == null && waitReinsert?.entry !== entry) {
                recordHitRect(entry, tmpSlotRect)
            }

            val isActive = initIdx == round.actingIndex &&
                turnEndLeaver == null &&
                defeatLeaver == null &&
                waitReinsert?.entry !== entry

            drawPortrait(
                canvas = canvas,
                rect = tmpSlotRect,
                entry = entry,
                combat = combat,
                isActive = isActive,
                alpha = alpha,
            )
        }
    }

    /**
     * Appends one hit-test entry, recycling a pooled [RectF] when
     * possible so the per-frame allocation stays at zero once
     * the pool has grown to the encounter's max simultaneously
     * rendered slot count.
     */
    private fun recordHitRect(entry: InitiativeEntry, rect: RectF) {
        if (hitCount < hitRectPool.size) {
            hitRectPool[hitCount].set(rect)
            hitEntries[hitCount] = entry
        } else {
            hitRectPool.add(RectF(rect))
            hitEntries.add(entry)
        }
        hitCount++
    }

    /**
     * Returns the topmost [InitiativeEntry] whose recorded hit
     * rect contains ([x], [y]), or null when the tap falls in
     * the gap between portraits / on empty backdrop. Walks the
     * cache front-to-back; with at most ~8 entries per round
     * the linear scan is cheap.
     */
    private fun hitTestPortrait(x: Float, y: Float): InitiativeEntry? {
        for (i in 0 until hitCount) {
            if (hitRectPool[i].contains(x, y)) return hitEntries[i]
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Reject the gesture early when there's nothing to
                // tap on - empty strip means the activity probably
                // wants the touch to fall through to whatever is
                // behind the bar (today it sits over the dungeon
                // edge, but the strip is gone in exploration mode
                // anyway).
                if (hitCount == 0) return false
                downX = event.x
                downY = event.y
                tracking = true
                true
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                tracking = false
                // Touch-slop check so a small finger drag doesn't
                // count as a tap - mirrors the same gate used by
                // HeroPanelView.
                val slop = ViewConfiguration.get(context).scaledTouchSlop
                val dx = event.x - downX
                val dy = event.y - downY
                if (dx * dx + dy * dy > slop.toFloat() * slop) return false

                val hit = hitTestPortrait(event.x, event.y) ?: return false
                onPortraitTapped?.invoke(hit)
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                false
            }
            else -> false
        }
    }

    /**
     * True when the combatant at initiative index [initIdx] has
     * already acted this round (hidden unless mid-animation).
     */
    private fun isSlotPast(initIdx: Int, round: CombatRound): Boolean {
        if (round.isRoundComplete) return true
        // Deferred heroes stay in the pending group at the tail until
        // their turn resolves — never in the "already acted" bucket.
        if (round.isDeferredInitiativeIndex(initIdx)) return false
        val qi = round.queueIndexOf(initIdx)
        if (qi < 0) return true
        return qi < round.queuePos
    }

    private fun displayedSlotFor(entry: InitiativeEntry, round: CombatRound): Int {
        val wr = round.waitReinsert
        if (wr != null && wr.entry === entry && wr.progress > 0f) {
            val p = wr.progress.coerceIn(0f, 1f)
            return lerp(-WAIT_OFFSCREEN_SLOTS, wr.toDisplayedSlot.toFloat(), p).toInt()
        }
        return round.displayedSlotOf(entry)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)

    private fun drawPortrait(
        canvas: Canvas,
        rect: RectF,
        entry: InitiativeEntry,
        combat: Combat,
        isActive: Boolean,
        alpha: Float,
    ) {
        // Background fill (color-coded by class / enemy).
        val bgColor = backgroundColorFor(entry, combat)
        portraitBgPaint.color = bgColor
        portraitBgPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
        val r = dp(4f)
        canvas.drawRoundRect(rect, r, r, portraitBgPaint)

        // Sprite (if loaded) or letter fallback.
        val sprite = spriteFor(entry, combat)
        if (sprite != null) {
            bitmapPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
            tmpSrcRect.set(0, 0, sprite.width, sprite.height)
            canvas.drawBitmap(sprite, tmpSrcRect, rect, bitmapPaint)
        } else {
            portraitLetterPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
            val letter = initialFor(entry, combat).toString()
            val cx = (rect.left + rect.right) / 2f
            val cy = (rect.top + rect.bottom) / 2f -
                (portraitLetterPaint.descent() + portraitLetterPaint.ascent()) / 2f
            canvas.drawText(letter, cx, cy, portraitLetterPaint)
        }

        // Border. Active slot gets the amber outline; everyone
        // else gets the muted gray.
        val borderPaint = if (isActive) portraitActiveBorderPaint else portraitBorderPaint
        val borderAlpha = (alpha * 255f).toInt().coerceIn(0, 255)
        val prevAlpha = borderPaint.alpha
        borderPaint.alpha = borderAlpha
        canvas.drawRoundRect(rect, r, r, borderPaint)
        borderPaint.alpha = prevAlpha
    }

    private fun backgroundColorFor(entry: InitiativeEntry, combat: Combat): Int =
        when (entry.kind) {
            InitiativeEntry.Kind.HERO -> {
                val hero = combat.party.heroes.getOrNull(entry.index)
                if (hero != null) heroClassColor(hero.heroClass) else COLOR_HERO_FALLBACK
            }
            InitiativeEntry.Kind.ENEMY -> COLOR_ENEMY_FILL
        }

    private fun initialFor(entry: InitiativeEntry, combat: Combat): Char =
        when (entry.kind) {
            InitiativeEntry.Kind.HERO -> {
                val hero = combat.party.heroes.getOrNull(entry.index)
                hero?.heroClass?.name?.first() ?: '?'
            }
            InitiativeEntry.Kind.ENEMY -> {
                combat.enemies.getOrNull(entry.index)?.name?.firstOrNull() ?: '?'
            }
        }

    /**
     * Lazily-loaded portrait for [entry]. Hero entries pull the
     * FIRST frame of the [PortraitCatalog] idle cycle for that
     * (class, gender) - i.e. the `*pic1.png` sprite - so the
     * turn-order strip shows a static still of each hero. The
     * full animation lives on the hero panel only; the strip is
     * a navigation HUD, not a stage for portrait performances.
     * Enemy entries return their [Enemy.template.portraitAsset]
     * if one is authored.
     */
    private fun spriteFor(entry: InitiativeEntry, combat: Combat): Bitmap? {
        val path = when (entry.kind) {
            InitiativeEntry.Kind.HERO -> {
                val hero: Hero = combat.party.heroes.getOrNull(entry.index) ?: return null
                PortraitCatalog.specFor(hero.heroClass, hero.gender)
                    .cycleAssets.firstOrNull() ?: return null
            }
            InitiativeEntry.Kind.ENEMY -> {
                val enemy: Enemy = combat.enemies.getOrNull(entry.index) ?: return null
                enemy.template.portraitAsset ?: return null
            }
        }
        return portraitCache.getOrPut(path) {
            runCatching {
                context.assets.open(path).use { BitmapFactory.decodeStream(it) }
            }.onFailure {
                Log.w(TAG, "Failed to load portrait $path: ${it.message}")
            }.getOrNull()
        }
    }

    // ----- Sizing helpers -----

    private fun portraitSlotSize(): Float {
        val padded = height - 2 * dp(STRIP_PADDING_VERTICAL_DP)
        return padded
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    private fun heroClassColor(cls: HeroClass): Int = when (cls) {
        HeroClass.MAGE -> Color.parseColor("#FF5A3C8C")
        HeroClass.THIEF -> Color.parseColor("#FF326E50")
        HeroClass.FIGHTER -> Color.parseColor("#FF963232")
        HeroClass.ARCHER -> Color.parseColor("#FFA08232")
    }

    companion object {
        private const val TAG = "TurnOrderBarView"

        /** Slide distance, expressed in (slot + gap) units. 2 = clears off-screen comfortably. */
        private const val SLIDE_SLOTS_FACTOR = 2f
        private const val WAIT_OFFSCREEN_SLOTS = 0.9f

        /**
         * Drop distance for the DEFEATED slide-down, in slot
         * heights. 1.5 lets the portrait fully clear the bar plus
         * leave a small trailing margin so the fade-out reads as
         * "exited" rather than "stalled at the edge".
         */
        private const val DEFEAT_DROP_FACTOR = 1.5f

        private const val SLOT_GAP_DP = 6f
        // STRIP_PADDING_DP (left-margin anchor) was removed when
        // the strip was switched to centering the group inside the
        // bar's width. The vertical padding stays - it's still the
        // safe-area between the portrait and the backdrop top/bottom.
        private const val STRIP_PADDING_VERTICAL_DP = 6f

        private val COLOR_BACKDROP = Color.parseColor("#CC0F0E0B")
        private val COLOR_PORTRAIT_BORDER = Color.parseColor("#FF5A4F38")
        private val COLOR_ACTIVE_BORDER = Color.parseColor("#FFFFC857")
        private val COLOR_ENEMY_FILL = Color.parseColor("#FF6A1F1F")
        private val COLOR_HERO_FALLBACK = Color.parseColor("#FF3A3A3A")

        /**
         * Convenience pass-through for callers that don't want to
         * carry [Hero] imports around.
         */
        @Suppress("unused")
        fun classNameInitial(cls: HeroClass): Char = cls.name.first()
    }
}
