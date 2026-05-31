package com.tavisdor.app.combat

/**
 * Round-level book-keeping that drives the top-of-screen turn
 * order strip.
 *
 * The strip shows portraits left-to-right in [roundQueue] order
 * (indices into the stable [initiative] list). As each combatant
 * finishes their action, their portrait slides off the left edge
 * while fading out ([Leaver.Kind.TURN_ENDED]). When a combatant is
 * defeated (HP 0), their portrait slides DOWN and fades
 * ([Leaver.Kind.DEFEATED]), then a [SHIFT_ANIM_DURATION_MS]
 * shift animates surviving portraits over to close the gap.
 *
 * A hero who **waits** is removed from their current queue slot,
 * survivors shift left, then the hero joins the **deferred tail**
 * at the end of [roundQueue]. Heroes in that tail stay sorted by
 * encounter initiative (higher DEX still acts before lower DEX
 * among everyone who waited). [waitReinsert] animates them into
 * their slot on the strip.
 */
class CombatRound(
    val initiative: List<InitiativeEntry>,
) {
    data class Leaver(
        val entry: InitiativeEntry,
        val progress: Float,
        val kind: Kind,
    ) {
        enum class Kind {
            TURN_ENDED,
            DEFEATED,
        }
    }

    /**
     * In-flight animation moving a deferred hero into the last
     * displayed slot after [completeCurrentActionWithWait].
     */
    data class WaitReinsert(
        val entry: InitiativeEntry,
        val progress: Float,
        val toDisplayedSlot: Int,
    )

    private val _roundQueue: MutableList<Int> = initiative.indices.toMutableList()

    /**
     * Initiative indices of heroes who declared Wait this round.
     * Rebuilt at the tail of [_roundQueue] in ascending initiative
     * order (same DEX ordering as the encounter list).
     */
    private val _deferredInitIndices: MutableSet<Int> = mutableSetOf()

    /** Initiative indices still pending this round, in turn order. */
    val roundQueue: List<Int> get() = _roundQueue

    /** Index into [roundQueue] for the actor who is up now. */
    var queuePos: Int = 0
        private set

    /**
     * Initiative index of the current actor, or -1 when the round
     * is exhausted but trailing animations are still resolving.
     */
    val actingIndex: Int
        get() = _roundQueue.getOrNull(queuePos) ?: -1

    var roundNumber: Int = 1
        private set

    private val _leavers: MutableList<Leaver> = mutableListOf()
    val leavers: List<Leaver> get() = _leavers

    private val _removedEntries: MutableSet<InitiativeEntry> = mutableSetOf()
    val removedEntries: Set<InitiativeEntry> get() = _removedEntries

    var shiftFromSlots: Map<InitiativeEntry, Int> = emptyMap()
        private set

    var shiftProgress: Float = 1f
        private set

    var waitReinsert: WaitReinsert? = null
        private set

    var heroActionsLockedThisRound: Boolean = false
        private set

    val isAnimating: Boolean
        get() = _leavers.isNotEmpty() || shiftProgress < 1f || waitReinsert != null

    val isRoundComplete: Boolean
        get() = queuePos >= _roundQueue.size

    val leaver: Leaver?
        get() = _leavers.firstOrNull { it.kind == Leaver.Kind.TURN_ENDED }

    fun currentActor(): InitiativeEntry? {
        val idx = _roundQueue.getOrNull(queuePos) ?: return null
        return initiative[idx]
    }

    /**
     * Jumps [queuePos] to [heroSlot]'s next pending turn this round so
     * an exploration ambush can resolve the attack immediately.
     */
    fun beginWithHeroActing(heroSlot: Int): Boolean {
        val initIdx = initiative.indexOfFirst {
            it.kind == InitiativeEntry.Kind.HERO && it.index == heroSlot
        }
        if (initIdx < 0) return false
        val queueIndex = _roundQueue.indexOf(initIdx)
        if (queueIndex < 0) return false
        queuePos = queueIndex
        return true
    }

    fun displayedSlotOf(entry: InitiativeEntry): Int {
        if (entry in _removedEntries) return -1
        var slot = 0
        for (qi in _roundQueue) {
            val e = initiative[qi]
            if (e === entry) return slot
            if (e !in _removedEntries) slot += 1
        }
        return -1
    }

    fun queueIndexOf(initiativeIndex: Int): Int =
        _roundQueue.indexOf(initiativeIndex)

    /** True while this hero has declared Wait and not yet taken their deferred turn. */
    fun isDeferredInitiativeIndex(initiativeIndex: Int): Boolean =
        initiativeIndex in _deferredInitIndices

    fun lockHeroActionsThisRound() {
        heroActionsLockedThisRound = true
    }

    fun completeCurrentAction() {
        check(queuePos in _roundQueue.indices) {
            "completeCurrentAction called with no actor (queuePos=$queuePos)."
        }
        val initIdx = _roundQueue[queuePos]
        _deferredInitIndices.remove(initIdx)
        _leavers.add(
            Leaver(
                entry = initiative[initIdx],
                progress = 0f,
                kind = Leaver.Kind.TURN_ENDED,
            ),
        )
        queuePos += 1
    }

    /**
     * Defer the current hero to the end of [roundQueue]. Returns
     * false when there is no later living hero to act first.
     */
    fun completeCurrentActionWithWait(): Boolean {
        if (queuePos !in _roundQueue.indices) return false
        val initIdx = _roundQueue[queuePos]
        val entry = initiative[initIdx]
        if (entry.kind != InitiativeEntry.Kind.HERO) return false
        if (countPendingTurnsAfterQueuePos() <= 0) return false

        shiftFromSlots = buildDisplayedSlotMap()
        _leavers.add(
            Leaver(
                entry = entry,
                progress = 0f,
                kind = Leaver.Kind.TURN_ENDED,
            ),
        )
        deferHeroToInitiativeOrderedTail(initIdx, queuePos)
        waitReinsert = WaitReinsert(
            entry = entry,
            progress = 0f,
            toDisplayedSlot = displayedSlotOf(entry).coerceAtLeast(0),
        )
        shiftProgress = 0f
        return true
    }

    /**
     * Moves a hero who has not acted yet this round (queue index
     * strictly after [queuePos]) into the deferred tail without
     * ending the current actor's turn. Used when the player declares
     * Wait from another hero's skill panel.
     */
    fun deferUpcomingHeroAt(queueIndex: Int): Boolean {
        if (queueIndex !in _roundQueue.indices || queueIndex <= queuePos) return false
        val initIdx = _roundQueue[queueIndex]
        val entry = initiative[initIdx]
        if (entry.kind != InitiativeEntry.Kind.HERO) return false

        // Re-base shifts so multiple heroes can defer during one acting turn.
        shiftFromSlots = buildDisplayedSlotMap()
        deferHeroToInitiativeOrderedTail(initIdx, queueIndex)
        // Slide within the pending strip to the tail — not the
        // turn-ended leaver / off-screen reinsert used when the
        // acting hero waits on their own turn.
        shiftProgress = 0f
        return true
    }

    /**
     * Pull [initIdx] out of the queue, mark it deferred, and rebuild
     * so every deferred hero sits at the end in initiative order.
     */
    private fun deferHeroToInitiativeOrderedTail(initIdx: Int, removeAt: Int) {
        _roundQueue.removeAt(removeAt)
        if (removeAt < queuePos) queuePos--
        _deferredInitIndices.add(initIdx)
        rebuildQueueWithDeferredTail()
    }

    private fun rebuildQueueWithDeferredTail() {
        val head = _roundQueue.filter { it !in _deferredInitIndices }
        val tail = sortedDeferredInitIndices()
        _roundQueue.clear()
        _roundQueue.addAll(head)
        _roundQueue.addAll(tail)
    }

    /**
     * Deferred heroes form the tail block: higher DEX first within that
     * block, lowest DEX at the absolute end of the round queue.
     */
    private fun sortedDeferredInitIndices(): List<Int> =
        _deferredInitIndices.sortedWith(
            compareByDescending<Int> { initiative[it].dexterity }
                .thenBy { it },
        )

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

        waitReinsert?.let { wr ->
            if (waitReinsertReady(wr)) {
                val newP = wr.progress + (deltaMs.toFloat() / WAIT_REINSERT_MS)
                if (newP < 1f) {
                    waitReinsert = wr.copy(progress = newP)
                } else {
                    waitReinsert = null
                }
                didWork = true
            }
        }

        if (_leavers.isEmpty() && shiftProgress >= 1f && waitReinsert == null && isRoundComplete) {
            startNextRound()
            didWork = true
        }

        return didWork
    }

    private fun waitReinsertReady(wr: WaitReinsert): Boolean {
        if (_leavers.any { it.entry === wr.entry }) return false
        return shiftProgress >= 1f
    }

    private fun countPendingTurnsAfterQueuePos(): Int {
        if (queuePos !in _roundQueue.indices) return 0
        var n = 0
        for (i in (queuePos + 1) until _roundQueue.size) {
            val entry = initiative[_roundQueue[i]]
            if (entry in _removedEntries) continue
            n++
        }
        return n
    }

    private fun buildDisplayedSlotMap(): Map<InitiativeEntry, Int> {
        val out = HashMap<InitiativeEntry, Int>()
        var slot = 0
        for (qi in _roundQueue) {
            val e = initiative[qi]
            if (e in _removedEntries) continue
            out[e] = slot
            slot += 1
        }
        return out
    }

    private fun startNextRound() {
        _roundQueue.clear()
        _roundQueue.addAll(initiative.indices)
        _deferredInitIndices.clear()
        queuePos = 0
        roundNumber += 1
        heroActionsLockedThisRound = false
        waitReinsert = null
        shiftProgress = 1f
        shiftFromSlots = emptyMap()
    }

    /**
     * Called when [queuePos] has walked off the end of [roundQueue]
     * but the encounter continues. Re-seeds the queue for a new
     * round when no strip animation is in flight.
     */
    fun startNextRoundIfComplete() {
        if (!isRoundComplete || isAnimating) return
        startNextRound()
    }

    companion object {
        const val LEAVE_ANIM_DURATION_MS: Float = 400f
        const val SHIFT_ANIM_DURATION_MS: Float = 250f
        const val WAIT_REINSERT_MS: Float = 420f
    }
}
