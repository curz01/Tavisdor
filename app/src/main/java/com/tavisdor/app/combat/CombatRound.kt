package com.tavisdor.app.combat

/**
 * Round-level book-keeping that drives the top-of-screen turn
 * order strip.
 *
 * The strip shows portraits left-to-right in initiative order. As
 * each combatant finishes their action, their portrait slides off
 * the left edge while fading out ([Leaver.Kind.TURN_ENDED]). When
 * a combatant is defeated (HP 0), their portrait slides DOWN and
 * fades ([Leaver.Kind.DEFEATED]), then a [SHIFT_ANIM_DURATION_MS]
 * shift animates surviving portraits over to close the gap.
 *
 * Multiple leaver animations can run in parallel - the common case
 * is a hero finishing their turn AND killing an enemy in the same
 * action, which spawns one TURN_ENDED leaver (slide left) and one
 * DEFEATED leaver (slide down) at the same time.
 *
 * State surfaces:
 *   - [actingIndex]:        whose turn it is right now.
 *   - [leavers]:            in-flight slide animations.
 *   - [removedEntries]:     combatants whose DEFEATED animation
 *                           completed; permanently hidden from the
 *                           strip.
 *   - [shiftFromSlots]:     snapshot of pre-removal displayed slot
 *                           indices, used by the view to interpolate
 *                           survivor X positions while [shiftProgress]
 *                           is in flight.
 *   - [shiftProgress]:      0..1 progress of the close-the-gap
 *                           animation; 1f when no shift is active.
 *
 * Rounds are derived implicitly: when [actingIndex] reaches the end
 * AND every leaver / shift animation finishes, [advanceAnimation]
 * automatically resets the round and increments [roundNumber].
 * [removedEntries] persists across rounds - once a combatant is
 * KO'd they stay gone.
 *
 * No real clock here - just wall-time deltas in millis fed from the
 * game loop. The view applies [Leaver.progress] to translate-X /
 * translate-Y / alpha curves, and [shiftProgress] to X
 * interpolation between [shiftFromSlots] and the current displayed
 * slot for each survivor.
 */
class CombatRound(
    val initiative: List<InitiativeEntry>,
) {
    /**
     * In-flight animation on a single combatant. [progress] runs
     * 0..1 over [LEAVE_ANIM_DURATION_MS]. The view picks the
     * translation axis from [kind] - TURN_ENDED slides left,
     * DEFEATED slides down.
     */
    data class Leaver(
        val entry: InitiativeEntry,
        /** 0 = animation just started, 1 = fully off-screen / faded. */
        val progress: Float,
        val kind: Kind,
    ) {
        enum class Kind {
            /** Combatant finished their action this round. */
            TURN_ENDED,

            /**
             * Combatant was reduced to 0 HP. After the animation
             * completes the entry moves into [removedEntries] and
             * the survivor strip closes the gap via [shiftProgress].
             */
            DEFEATED,
        }
    }

    /**
     * Index into [initiative] of the combatant whose turn is up
     * right now. -1 once the round is exhausted but trailing
     * leaver / shift animations are still resolving; reset to 0
     * when the next round begins.
     */
    var actingIndex: Int = 0
        private set

    /** Increments each time a round wraps; useful for UI / save data. */
    var roundNumber: Int = 1
        private set

    private val _leavers: MutableList<Leaver> = mutableListOf()
    val leavers: List<Leaver> get() = _leavers

    private val _removedEntries: MutableSet<InitiativeEntry> = mutableSetOf()

    /**
     * Combatants whose DEFEATED animation has finished. The strip
     * filters these out of rendering; the controller leaves them
     * in [initiative] so initiative indices stay stable across
     * rounds.
     */
    val removedEntries: Set<InitiativeEntry> get() = _removedEntries

    /**
     * Snapshot of displayed-slot indices BEFORE the most recent
     * defeat finalized. Populated when a DEFEATED leaver completes,
     * cleared when [shiftProgress] hits 1. The view reads this
     * to interpolate each surviving portrait's X from "before the
     * gap collapsed" to "after the gap collapsed".
     */
    var shiftFromSlots: Map<InitiativeEntry, Int> = emptyMap()
        private set

    /**
     * 0..1 progress of the shift animation. 1.0f = no shift in
     * flight, surviving portraits sit at their current displayed
     * slots. < 1f = view should interpolate each survivor's X
     * between [shiftFromSlots] and its current displayed slot.
     */
    var shiftProgress: Float = 1f
        private set

    /**
     * Latched when the party successfully disengages: every
     * still-pending HERO turn this round is auto-skipped (the
     * controller still slides the portrait off the strip so the
     * round-end visual cadence reads cleanly). Enemy turns are
     * unaffected - they keep firing in initiative order until the
     * round wraps.
     *
     * Reset to false in [startNextRound] so the next round opens
     * with full party agency again.
     */
    var heroActionsLockedThisRound: Boolean = false
        private set

    /**
     * Latches [heroActionsLockedThisRound]. Called by the combat
     * controller right after a successful disengage; idempotent.
     */
    fun lockHeroActionsThisRound() {
        heroActionsLockedThisRound = true
    }

    /**
     * True iff any leaver or shift animation is still running. The
     * combat controller polls this to decide when to queue the
     * next actor.
     */
    val isAnimating: Boolean
        get() = _leavers.isNotEmpty() || shiftProgress < 1f

    /** True iff every combatant in [initiative] has acted this round. */
    val isRoundComplete: Boolean
        get() = actingIndex < 0

    /**
     * Legacy single-leaver accessor: returns the TURN_ENDED leaver
     * for the most recent action, or null if there isn't one in
     * flight. Kept so older view code that only knew about the
     * single-leaver model still compiles.
     */
    val leaver: Leaver?
        get() = _leavers.firstOrNull { it.kind == Leaver.Kind.TURN_ENDED }

    /**
     * The combatant whose turn it is right now, or null when the
     * round just finished and we're waiting for animations to
     * resolve.
     */
    fun currentActor(): InitiativeEntry? =
        if (actingIndex in initiative.indices) initiative[actingIndex] else null

    /**
     * Current displayed slot index (0-based, left-to-right) for
     * [entry]. Skips entries that have been [removedEntries]'d,
     * so survivors after a fallen comrade shift left. Returns -1
     * when [entry] is removed or not in [initiative].
     */
    fun displayedSlotOf(entry: InitiativeEntry): Int {
        if (entry in _removedEntries) return -1
        var slot = 0
        for (e in initiative) {
            if (e === entry) return slot
            if (e !in _removedEntries) slot += 1
        }
        return -1
    }

    /**
     * Call when the current actor has finished their action. Spawns
     * a TURN_ENDED leaver and advances [actingIndex] to the next
     * combatant. If the round is now exhausted, [actingIndex]
     * becomes -1 - the round will auto-reset once all animations
     * finish (see [advanceAnimation]).
     *
     * Idempotent-ish: only valid when [actingIndex] points at a
     * live initiative entry; throws otherwise to surface state
     * bugs early.
     */
    fun completeCurrentAction() {
        val current = actingIndex
        check(current in initiative.indices) {
            "completeCurrentAction called with no actor (actingIndex=$current)."
        }
        _leavers.add(
            Leaver(
                entry = initiative[current],
                progress = 0f,
                kind = Leaver.Kind.TURN_ENDED,
            ),
        )
        actingIndex = if (current + 1 >= initiative.size) -1 else current + 1
    }

    /**
     * Spawn a DEFEATED leaver for [entry]. Safe to call multiple
     * times - duplicates against an already-removed or already-
     * dying entry are ignored. Triggered by the combat controller
     * the moment a combatant's HP hits 0.
     */
    fun markDefeated(entry: InitiativeEntry) {
        if (entry in _removedEntries) return
        if (_leavers.any { it.entry === entry && it.kind == Leaver.Kind.DEFEATED }) return
        _leavers.add(
            Leaver(
                entry = entry,
                progress = 0f,
                kind = Leaver.Kind.DEFEATED,
            ),
        )
    }

    /**
     * Advance every active animation by [deltaMs] millis. Returns
     * true when the renderer should redraw on the next frame
     * (animations still running OR something just transitioned).
     *
     * Sequencing details:
     *   - Each leaver advances independently. A TURN_ENDED leaver
     *     just disappears when its progress hits 1.
     *   - A DEFEATED leaver that hits progress 1 captures the
     *     current displayed-slot map (pre-removal), then moves its
     *     entry into [removedEntries] and starts the shift
     *     animation by setting [shiftProgress] = 0.
     *   - The shift animation advances on its own [SHIFT_ANIM_DURATION_MS]
     *     clock. Once it reaches 1, [shiftFromSlots] is cleared.
     *   - When everything is idle AND [actingIndex] is -1, the
     *     round auto-resets to 0 and [roundNumber] increments.
     */
    fun advanceAnimation(deltaMs: Long): Boolean {
        var didWork = false

        if (_leavers.isNotEmpty()) {
            val completed = mutableListOf<Leaver>()
            for (i in _leavers.indices) {
                val l = _leavers[i]
                val newP = l.progress + (deltaMs.toFloat() / LEAVE_ANIM_DURATION_MS)
                if (newP < 1f) {
                    _leavers[i] = l.copy(progress = newP)
                } else {
                    completed.add(l)
                }
            }
            if (completed.isNotEmpty()) {
                _leavers.removeAll(completed)
                for (c in completed) {
                    if (c.kind == Leaver.Kind.DEFEATED) {
                        // Snapshot displayed slots BEFORE removing
                        // so the shift animation can interpolate
                        // survivor X positions from "as they were"
                        // to "after the gap collapsed".
                        shiftFromSlots = buildDisplayedSlotMap()
                        _removedEntries.add(c.entry)
                        shiftProgress = 0f
                    }
                }
            }
            didWork = true
        }

        if (shiftProgress < 1f) {
            val newProgress = shiftProgress + (deltaMs.toFloat() / SHIFT_ANIM_DURATION_MS)
            shiftProgress = newProgress.coerceAtMost(1f)
            if (shiftProgress >= 1f) {
                shiftFromSlots = emptyMap()
            }
            didWork = true
        }

        if (_leavers.isEmpty() && shiftProgress >= 1f && actingIndex < 0) {
            startNextRound()
            didWork = true
        }

        return didWork
    }

    /**
     * Builds a snapshot of the CURRENT displayed-slot index for
     * every still-rendered entry (i.e. those not yet in
     * [removedEntries]). Used as the "from" state of the shift
     * animation when a DEFEATED leaver completes.
     */
    private fun buildDisplayedSlotMap(): Map<InitiativeEntry, Int> {
        val out = HashMap<InitiativeEntry, Int>()
        var slot = 0
        for (e in initiative) {
            if (e in _removedEntries) continue
            out[e] = slot
            slot += 1
        }
        return out
    }

    private fun startNextRound() {
        actingIndex = 0
        roundNumber += 1
        // Disengage lock is per-round; the next round opens with
        // full party agency again so heroes can swing / cast / move.
        heroActionsLockedThisRound = false
    }

    companion object {
        /** Duration of the portrait slide-off + fade-out animation. */
        const val LEAVE_ANIM_DURATION_MS: Float = 400f

        /**
         * Duration of the "close the gap" shift animation that
         * runs AFTER a DEFEATED leaver finishes. Shorter than
         * [LEAVE_ANIM_DURATION_MS] so the visual cadence reads as
         * "death, then quick repack" rather than two equally
         * weighted beats.
         */
        const val SHIFT_ANIM_DURATION_MS: Float = 250f
    }
}
