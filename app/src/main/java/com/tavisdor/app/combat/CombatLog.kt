package com.tavisdor.app.combat

/**
 * Bounded scrolling history of [CombatLogEntry] rows displayed by
 * [com.tavisdor.app.ui.CombatLogView] above the action bar.
 *
 * The log keeps the most recent [capacity] entries; older entries
 * are silently evicted when [append] exceeds the bound. Insertion
 * order is preserved so the renderer can show oldest-at-top /
 * newest-at-bottom without re-sorting.
 *
 * Listeners ([onChanged]) fire on every state mutation so the view
 * can invalidate without polling. The log itself is intentionally
 * UI-agnostic: it stores typed entries and lets the view decide
 * how to format them.
 */
class CombatLog(
    /**
     * Maximum number of entries retained. 4 matches the on-screen
     * row count - keeping the model in sync with the view avoids
     * confusion about whether off-screen rows still "exist."
     */
    val capacity: Int = DEFAULT_CAPACITY,
) {

    private val _entries: ArrayDeque<CombatLogEntry> = ArrayDeque(capacity)

    /**
     * Snapshot of the current entries, oldest first. Returns a
     * defensive copy so callers can iterate without worrying about
     * concurrent appends from the combat tick.
     */
    val entries: List<CombatLogEntry>
        get() = _entries.toList()

    /**
     * Notified whenever the log mutates. The view uses this to
     * post an [android.view.View.invalidate] / re-build its
     * spannable text.
     */
    var onChanged: (() -> Unit)? = null

    /**
     * Append [entry] to the tail of the log; if size exceeds
     * [capacity], the head is dropped. Fires [onChanged] once.
     */
    fun append(entry: CombatLogEntry) {
        _entries.addLast(entry)
        while (_entries.size > capacity) {
            _entries.removeFirst()
        }
        onChanged?.invoke()
    }

    /**
     * Drop every stored entry. Called at the start of a fresh
     * encounter so prior fights don't leak into the new log.
     */
    fun clear() {
        if (_entries.isEmpty()) return
        _entries.clear()
        onChanged?.invoke()
    }

    companion object {
        /**
         * Default rolling window size. Larger than the visible
         * row count so the player can scroll back through recent
         * history via the up / down buttons on [CombatLogView];
         * older entries beyond this cap are silently evicted to
         * keep the in-memory log bounded.
         */
        const val DEFAULT_CAPACITY: Int = 10

        /**
         * Number of entries the view renders at once. Anything
         * past this lives in the scrollable history. Tracked here
         * so [CombatLogView] and downstream layout code reference
         * a single source of truth instead of duplicating the
         * row count.
         */
        const val VISIBLE_ROWS: Int = 4
    }
}
