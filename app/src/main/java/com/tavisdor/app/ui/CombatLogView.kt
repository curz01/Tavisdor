package com.tavisdor.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import androidx.appcompat.widget.AppCompatTextView
import com.tavisdor.app.combat.CombatLog
import com.tavisdor.app.combat.CombatLogEntry

/**
 * Four-line combat log sitting above the Menu / Action / Items
 * action bar. Renders the last [CombatLog.capacity] entries as a
 * color-coded multi-line text block:
 *
 *   - Names and connectives ("attacks", "but", "for") render in
 *     [COLOR_TEXT] (white-ish), matching the rest of the in-game
 *     HUD chrome.
 *   - Damage verbs ("attacks", "casts") and damage amounts
 *     ("for 5 damage") render in [COLOR_ATTACK] (red).
 *   - Critical hits and defeats use [COLOR_CRIT] (brighter red).
 *   - Elemental advantage / disadvantage tags use accent colors so
 *     the player can see at a glance which way the matchup tilted.
 *
 * The view is a pure observer of a [CombatLog] - rebind via
 * [setLog] when combat starts/ends. It listens to the log's
 * [CombatLog.onChanged] callback and rebuilds the spannable in
 * place, no per-frame work.
 *
 * Layout-wise the view holds a fixed [CombatLog.VISIBLE_ROWS]-line
 * height; per-entry text is ellipsised at the end so a very long
 * line (e.g. a full name + spell + damage) truncates instead of
 * wrapping and pushing the next entry off-screen.
 *
 * The log keeps up to [CombatLog.DEFAULT_CAPACITY] entries; the
 * player can scroll through them via [scrollUp] / [scrollDown]
 * (typically wired to two buttons rendered next to the view).
 * New entries auto-snap the scroll back to the most recent row
 * so an active combat reads naturally; the player has to opt
 * into history mode by tapping the up button.
 */
class CombatLogView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var log: CombatLog? = null

    /**
     * How many entries we've scrolled back from the newest. 0
     * means "show the most recent [CombatLog.VISIBLE_ROWS]
     * entries with the latest on the bottom row". Larger values
     * shift the window toward older entries. Capped per
     * [maxScrollOffset] on every rebuild so it never points
     * past the head of the log.
     */
    private var scrollOffset: Int = 0

    /**
     * Notified whenever the scroll position OR the underlying
     * log changes. Hosts can use this to refresh the enabled
     * state of the scroll-up / scroll-down buttons in lock-step
     * with [canScrollUp] / [canScrollDown].
     */
    var onScrollStateChanged: (() -> Unit)? = null

    init {
        setBackgroundColor(COLOR_BACKDROP)
        setTextColor(COLOR_TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.create("monospace", Typeface.NORMAL)
        // Fixed VISIBLE_ROWS height. Ellipsize so a wide string
        // never wraps onto a second line and pushes the next
        // entry off-screen.
        minLines = CombatLog.VISIBLE_ROWS
        maxLines = CombatLog.VISIBLE_ROWS
        setLines(CombatLog.VISIBLE_ROWS)
        ellipsize = TextUtils.TruncateAt.END
        setHorizontallyScrolling(false)
        gravity = Gravity.TOP or Gravity.START
        val padPx = dp(6f).toInt()
        setPadding(padPx, padPx, padPx, padPx)
        // Tighter line spacing so the visible rows fit comfortably
        // above the action bar without crowding the panel below.
        setLineSpacing(0f, 1.05f)
        includeFontPadding = false
        text = ""
    }

    // -----------------------------------------------------------------
    // Public binding
    // -----------------------------------------------------------------

    /**
     * Bind the view to a log instance. Passing null clears the
     * text and detaches the listener. Re-binding to the same log
     * is cheap (no-op listener swap).
     */
    fun setLog(log: CombatLog?) {
        this.log?.onChanged = null
        this.log = log
        log?.onChanged = { onLogChanged() }
        scrollOffset = 0
        rebuild()
    }

    // -----------------------------------------------------------------
    // Scroll API (driven by external up / down buttons)
    // -----------------------------------------------------------------

    /** Max scroll-back position. 0 when the log fits in one window. */
    private fun maxScrollOffset(): Int {
        val l = log ?: return 0
        return (l.entries.size - CombatLog.VISIBLE_ROWS).coerceAtLeast(0)
    }

    /** True iff there's at least one older entry hidden above the window. */
    val canScrollUp: Boolean get() = scrollOffset < maxScrollOffset()

    /** True iff we've scrolled at least one entry away from the newest. */
    val canScrollDown: Boolean get() = scrollOffset > 0

    /**
     * Step one entry toward older history. No-op when already at
     * the head; otherwise rebuilds the visible window and fires
     * [onScrollStateChanged].
     */
    fun scrollUp() {
        if (!canScrollUp) return
        scrollOffset = (scrollOffset + 1).coerceAtMost(maxScrollOffset())
        rebuild()
        onScrollStateChanged?.invoke()
    }

    /**
     * Step one entry toward newer history. No-op when already at
     * the latest entry; otherwise rebuilds and fires
     * [onScrollStateChanged].
     */
    fun scrollDown() {
        if (!canScrollDown) return
        scrollOffset = (scrollOffset - 1).coerceAtLeast(0)
        rebuild()
        onScrollStateChanged?.invoke()
    }

    // -----------------------------------------------------------------
    // Render pipeline
    // -----------------------------------------------------------------

    /**
     * Log mutation callback. Snaps the view back to the latest
     * entry (offset 0) so new combat events are always immediately
     * visible, then rebuilds and notifies scroll-state listeners
     * so any host UI (e.g. up / down arrow enabling) can refresh
     * in lock-step.
     */
    private fun onLogChanged() {
        scrollOffset = 0
        rebuild()
        onScrollStateChanged?.invoke()
    }

    /**
     * Rebuilds the spannable from the current [CombatLog.entries]
     * snapshot, sliced to the visible window defined by
     * [scrollOffset]. Cheap enough to run synchronously from the
     * combat tick.
     */
    private fun rebuild() {
        val l = log
        if (l == null) {
            text = ""
            return
        }
        val all = l.entries
        // Defensive clamp - capacity shrinks (or log.clear) could
        // strand the offset past the new tail; pin it back into
        // range before slicing.
        val maxOff = (all.size - CombatLog.VISIBLE_ROWS).coerceAtLeast(0)
        if (scrollOffset > maxOff) scrollOffset = maxOff

        val end = all.size - scrollOffset
        val start = (end - CombatLog.VISIBLE_ROWS).coerceAtLeast(0)
        val window = if (all.isEmpty()) emptyList() else all.subList(start, end)

        val ssb = SpannableStringBuilder()
        for ((i, entry) in window.withIndex()) {
            if (i > 0) ssb.append("\n")
            appendEntry(ssb, entry)
        }
        text = ssb
    }

    /**
     * Dispatches to a per-variant formatter. Each formatter
     * appends styled segments to [out] via the [whiteSeg] /
     * [attackSeg] / [critSeg] helpers below; no formatter is
     * allowed to append a trailing newline (the outer loop in
     * [rebuild] handles row joins).
     */
    private fun appendEntry(out: SpannableStringBuilder, entry: CombatLogEntry) {
        when (entry) {
            is CombatLogEntry.MeleeHit -> {
                whiteSeg(out, entry.attacker)
                whiteSeg(out, " ")
                attackSeg(out, if (entry.crit) "lands a critical hit on" else "attacks")
                whiteSeg(out, " ${entry.target} ")
                attackSeg(out, "for ${entry.damage} damage.")
            }
            is CombatLogEntry.MeleeNoDamage -> {
                whiteSeg(out, entry.attacker)
                whiteSeg(out, " ")
                attackSeg(out, "attacks")
                whiteSeg(out, " ${entry.target} ")
                whiteSeg(out, "but deals no damage.")
            }
            is CombatLogEntry.MeleeMiss -> {
                whiteSeg(out, entry.attacker)
                whiteSeg(out, " ")
                attackSeg(out, "attacks")
                whiteSeg(out, " but ${entry.target} dodges the attack.")
            }
            is CombatLogEntry.SpellHit -> {
                whiteSeg(out, entry.attacker)
                whiteSeg(out, " ")
                attackSeg(out, "casts")
                whiteSeg(out, " ${entry.spellName} on ${entry.target} ")
                attackSeg(out, "for ${entry.damage} damage")
                when {
                    entry.advantage -> {
                        whiteSeg(out, " ")
                        advantageSeg(out, "(super effective!)")
                    }
                    entry.disadvantage -> {
                        whiteSeg(out, " ")
                        disadvantageSeg(out, "(resisted)")
                    }
                    else -> whiteSeg(out, ".")
                }
            }
            is CombatLogEntry.SpellResist -> {
                whiteSeg(out, entry.attacker)
                whiteSeg(out, " ")
                attackSeg(out, "casts")
                whiteSeg(out, " ${entry.spellName} ")
                whiteSeg(out, "but ${entry.target} resists.")
            }
            is CombatLogEntry.HealCast -> {
                whiteSeg(out, entry.caster)
                whiteSeg(out, " ")
                healSeg(out, "casts")
                whiteSeg(out, " ${entry.spellName} on ${entry.target} ")
                // amount = 0 means the target was already at full HP.
                // Phrase it differently so the player understands the
                // turn wasn't wasted by the resolver, the heal just
                // had nothing to top up.
                if (entry.amount > 0) {
                    healSeg(out, "for +${entry.amount} HP.")
                } else {
                    whiteSeg(out, "but it has no effect.")
                }
            }
            is CombatLogEntry.Defeat -> {
                critSeg(out, "${entry.name} is defeated!")
            }
            CombatLogEntry.Victory -> {
                advantageSeg(out, "Enemies defeated.")
            }
            is CombatLogEntry.GoldAwarded -> {
                whiteSeg(out, "The party loots ")
                xpSeg(out, "${entry.amount}")
                whiteSeg(out, " gold.")
            }
            CombatLogEntry.PartyWipe -> {
                critSeg(out, "The party is wiped out!")
            }
            is CombatLogEntry.XpGained -> {
                whiteSeg(out, "${entry.name} gains ")
                xpSeg(out, "${entry.amount}")
                whiteSeg(out, " experience.")
            }
            is CombatLogEntry.LevelUp -> {
                whiteSeg(out, "${entry.name} reaches level ")
                levelUpSeg(out, "${entry.newLevel}")
                whiteSeg(out, "!")
            }
            is CombatLogEntry.SkillUnlocked -> {
                whiteSeg(out, "${entry.name} learns ")
                levelUpSeg(out, entry.skillName)
                whiteSeg(out, ".")
            }
            is CombatLogEntry.StatChange -> {
                whiteSeg(out, "${entry.name}: ")
                entry.deltas.forEachIndexed { idx, delta ->
                    if (idx > 0) whiteSeg(out, ", ")
                    whiteSeg(out, "${delta.label} ${delta.before}")
                    advantageSeg(out, " -> ${delta.after}")
                }
            }
            is CombatLogEntry.DisengageSuccess -> {
                whiteSeg(out, "${entry.heroName} leads the party past the enemy line.")
            }
            is CombatLogEntry.DisengageFail -> {
                whiteSeg(out, "${entry.heroName} fails to disengage - ")
                attackSeg(out, "${entry.blockedByName} holds the party!")
            }
            CombatLogEntry.DisengageSurrounded -> {
                critSeg(out, "The party is surrounded - disengage impossible!")
            }
            CombatLogEntry.PartyMoved -> {
                whiteSeg(out, "The party repositions.")
            }
            is CombatLogEntry.HeroCharged -> {
                whiteSeg(out, "${entry.heroName} charges ${entry.distance} ")
                whiteSeg(out, if (entry.distance == 1) "square" else "squares")
                whiteSeg(out, " at ${entry.targetName}.")
            }
            is CombatLogEntry.DefendBraced -> {
                whiteSeg(out, "${entry.name} braces for impact")
                if (entry.auto) {
                    whiteSeg(out, " - no target in range.")
                } else {
                    whiteSeg(out, ".")
                }
            }
            is CombatLogEntry.OutOfRange -> {
                whiteSeg(out, "${entry.attacker} can't reach ${entry.target} with ")
                whiteSeg(out, "${entry.skillName} - ")
                disadvantageSeg(out, "out of range.")
            }
            is CombatLogEntry.LineOfSightBlocked -> {
                whiteSeg(out, "${entry.attacker}'s ${entry.skillName} is ")
                disadvantageSeg(out, "blocked")
                whiteSeg(out, " - no clear shot at ${entry.target}.")
            }
            is CombatLogEntry.MissingShard -> {
                whiteSeg(out, "${entry.attacker} needs a ")
                whiteSeg(out, "${entry.shardName}")
                whiteSeg(out, " to use ")
                whiteSeg(out, "${entry.skillName}.")
            }
            is CombatLogEntry.Info -> {
                whiteSeg(out, entry.text)
            }
        }
    }

    // -----------------------------------------------------------------
    // Span helpers - thin wrappers around SpannableStringBuilder to
    // keep the variant formatters terse.
    // -----------------------------------------------------------------

    private fun whiteSeg(out: SpannableStringBuilder, text: String) {
        appendSpan(out, text, COLOR_TEXT, bold = false)
    }

    private fun attackSeg(out: SpannableStringBuilder, text: String) {
        appendSpan(out, text, COLOR_ATTACK, bold = false)
    }

    private fun critSeg(out: SpannableStringBuilder, text: String) {
        appendSpan(out, text, COLOR_CRIT, bold = true)
    }

    private fun advantageSeg(out: SpannableStringBuilder, text: String) {
        appendSpan(out, text, COLOR_ADVANTAGE, bold = false)
    }

    private fun disadvantageSeg(out: SpannableStringBuilder, text: String) {
        appendSpan(out, text, COLOR_DISADVANTAGE, bold = false)
    }

    /** Soft gold for XP amounts - reward color, not a damage color. */
    private fun xpSeg(out: SpannableStringBuilder, text: String) {
        appendSpan(out, text, COLOR_XP, bold = true)
    }

    /**
     * Green for heal verbs and HP-restored amounts. Sits opposite
     * the red attack/damage palette so the player can scan the log
     * and tell heals from hits at a glance.
     */
    private fun healSeg(out: SpannableStringBuilder, text: String) {
        appendSpan(out, text, COLOR_HEAL, bold = false)
    }

    /**
     * Bright gold + bold for level-up callouts (level number,
     * unlocked skill name). Distinct from [xpSeg] so the level-up
     * line reads as a milestone, not a tally.
     */
    private fun levelUpSeg(out: SpannableStringBuilder, text: String) {
        appendSpan(out, text, COLOR_LEVEL_UP, bold = true)
    }

    private fun appendSpan(
        out: SpannableStringBuilder,
        text: String,
        color: Int,
        bold: Boolean,
    ) {
        val start = out.length
        out.append(text)
        val end = out.length
        out.setSpan(
            ForegroundColorSpan(color),
            start, end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        if (bold) {
            out.setSpan(
                StyleSpan(Typeface.BOLD),
                start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    companion object {
        /** Semi-transparent black behind the log; ~55% opacity. */
        private val COLOR_BACKDROP: Int = Color.argb(0x90, 0x00, 0x00, 0x00)

        /** Default body color - off-white to match the rest of the HUD. */
        private val COLOR_TEXT: Int = Color.parseColor("#FFF5ECD2")

        /** Standard attack verb / damage amount color. */
        private val COLOR_ATTACK: Int = Color.parseColor("#FFE54D4D")

        /** Brighter red, reserved for crits and KOs. */
        private val COLOR_CRIT: Int = Color.parseColor("#FFFF7676")

        /** Elemental advantage tag color (warm orange). */
        private val COLOR_ADVANTAGE: Int = Color.parseColor("#FFFFB347")

        /** Elemental disadvantage / resist tag color (cool blue). */
        private val COLOR_DISADVANTAGE: Int = Color.parseColor("#FF7FBFFF")

        /** Soft gold for XP awards. */
        private val COLOR_XP: Int = Color.parseColor("#FFE6C66B")

        /** Bright gold for level-up callouts. */
        private val COLOR_LEVEL_UP: Int = Color.parseColor("#FFFFD24F")

        /**
         * Soft green for heal verbs and HP-restored amounts. Sits
         * opposite the [COLOR_ATTACK] red so heals and damage are
         * instantly distinguishable when the player scans the log.
         */
        private val COLOR_HEAL: Int = Color.parseColor("#FF7FD17F")
    }
}
