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
    /** Maximum number of entries retained (rolling window). */
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
        /** Default rolling window (oldest entries evicted beyond this). */
        const val DEFAULT_CAPACITY: Int = 20
    }
}
