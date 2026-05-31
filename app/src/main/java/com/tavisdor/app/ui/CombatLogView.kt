package com.tavisdor.app.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ScrollView
import androidx.appcompat.widget.AppCompatTextView
import com.tavisdor.app.R
import com.tavisdor.app.combat.CombatLog
import com.tavisdor.app.combat.CombatLogEntry

/**
 * Combat log above the Menu / Action / Items bar. Renders up to
 * [CombatLog.DEFAULT_CAPACITY] entries with color-coded spans; long lines
 * wrap. The parent [ScrollView] ([R.id.combatLogScroll]) provides a fixed
 * viewport — swipe up/down to scroll. Tap to double height; tap outside to
 * collapse (handled by [com.tavisdor.app.MainActivity]).
 */
class CombatLogView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var log: CombatLog? = null
    private var scrollHost: ScrollView? = null
    private var expanded: Boolean = false

    var onExpandedChanged: ((expanded: Boolean) -> Unit)? = null

    val isExpanded: Boolean get() = expanded

    init {
        setBackgroundColor(COLOR_BACKDROP)
        setTextColor(COLOR_TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        typeface = Typeface.create("monospace", Typeface.NORMAL)
        setHorizontallyScrolling(false)
        isSingleLine = false
        gravity = Gravity.TOP or Gravity.START
        val padPx = dp(6f).toInt()
        setPadding(padPx, padPx, padPx, padPx)
        setLineSpacing(0f, 1.05f)
        includeFontPadding = false
        isClickable = true
        text = ""
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scrollHost = findScrollHost()
        applyPanelHeight()
    }

    fun setLog(log: CombatLog?) {
        this.log?.onChanged = null
        this.log = log
        log?.onChanged = { onLogChanged() }
        rebuild()
        scrollToBottom()
    }

    fun setExpanded(expanded: Boolean) {
        if (this.expanded == expanded) return
        val collapsing = this.expanded && !expanded
        this.expanded = expanded
        applyPanelHeight()
        if (collapsing) {
            scrollToBottomAfterResize()
        }
        onExpandedChanged?.invoke(expanded)
    }

    private fun findScrollHost(): ScrollView? {
        var parent = parent
        while (parent != null) {
            if (parent is ScrollView) return parent
            parent = parent.parent
        }
        return null
    }

    private fun onLogChanged() {
        val stickToBottom = isScrolledToBottom()
        rebuild()
        if (stickToBottom) {
            scrollToBottom()
        }
    }

    private fun rebuild() {
        val l = log
        if (l == null) {
            text = ""
            return
        }
        val ssb = SpannableStringBuilder()
        for ((i, entry) in l.entries.withIndex()) {
            if (i > 0) ssb.append("\n")
            appendEntry(ssb, entry)
        }
        text = ssb
    }

    private fun isScrolledToBottom(): Boolean {
        val scroll = scrollHost ?: return true
        val child = scroll.getChildAt(0) ?: return true
        val slack = dp(4f).toInt()
        return scroll.scrollY + scroll.height >= child.height - slack
    }

    private fun scrollToBottom() {
        val scroll = scrollHost ?: return
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** After collapse, show the newest log lines in the shorter viewport. */
    private fun scrollToBottomAfterResize() {
        val scroll = scrollHost ?: return
        scroll.post {
            scroll.fullScroll(View.FOCUS_DOWN)
            // Second pass once the collapsed height has been laid out.
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun applyPanelHeight() {
        val scroll = scrollHost ?: return
        val heightRes = if (expanded) {
            R.dimen.combat_log_expanded_height
        } else {
            R.dimen.combat_log_collapsed_height
        }
        val targetPx = resources.getDimensionPixelSize(heightRes)
        val lp = scroll.layoutParams ?: return
        lp.height = targetPx
        scroll.layoutParams = lp
        scroll.requestLayout()
    }

    private fun appendEntry(out: SpannableStringBuilder, entry: CombatLogEntry) {
        when (entry) {
            is CombatLogEntry.MeleeHit -> {
                whiteSeg(out, entry.attacker)
                whiteSeg(out, " ")
                if (entry.skillName != null) {
                    attackSeg(out, "uses")
                    whiteSeg(out, " ${entry.skillName} on ${entry.target} ")
                    attackSeg(out, "for ${entry.damage} damage.")
                } else {
                    attackSeg(out, if (entry.crit) "lands a critical hit on" else "attacks")
                    whiteSeg(out, " ${entry.target} ")
                    attackSeg(out, "for ${entry.damage} damage.")
                }
            }
            is CombatLogEntry.MeleeNoDamage -> {
                whiteSeg(out, entry.attacker)
                whiteSeg(out, " ")
                if (entry.skillName != null) {
                    attackSeg(out, "uses")
                    whiteSeg(out, " ${entry.skillName} on ${entry.target} ")
                    whiteSeg(out, "but deals no damage.")
                } else {
                    attackSeg(out, "attacks")
                    whiteSeg(out, " ${entry.target} ")
                    whiteSeg(out, "but deals no damage.")
                }
            }
            is CombatLogEntry.MeleeMiss -> {
                whiteSeg(out, entry.attacker)
                whiteSeg(out, " ")
                if (entry.skillName != null) {
                    attackSeg(out, "uses")
                    whiteSeg(out, " ${entry.skillName}")
                } else {
                    attackSeg(out, "attacks")
                }
                if (entry.shotLabel != null) {
                    whiteSeg(out, " (${entry.shotLabel})")
                }
                whiteSeg(out, " but ${entry.target} dodges.")
            }
            is CombatLogEntry.SpellHit -> {
                whiteSeg(out, entry.attacker)
                whiteSeg(out, " ")
                attackSeg(out, if (entry.crit) "lands a critical" else "casts")
                whiteSeg(out, " ${entry.spellName} on ${entry.target} ")
                attackSeg(out, "for ${entry.damage} damage")
                when {
                    entry.advantage -> {
                        whiteSeg(out, " ")
                        advantageSeg(out, "(super effective!)")
                    }
                    entry.disadvantage -> {
                        whiteSeg(out, " ")
                        disadvantageSeg(out, if (entry.crit) "(crit — less resisted)" else "(resisted)")
                    }
                    else -> whiteSeg(out, if (entry.crit) " (critical)." else ".")
                }
            }
            is CombatLogEntry.SpellResist -> {
                whiteSeg(out, entry.attacker)
                whiteSeg(out, " ")
                attackSeg(out, "casts")
                whiteSeg(out, " ${entry.spellName} ")
                whiteSeg(out, "but ${entry.target} resists.")
            }
            is CombatLogEntry.ElementalBonusHit -> {
                whiteSeg(out, entry.attacker)
                whiteSeg(out, "'s ")
                attackSeg(out, entry.skillName)
                whiteSeg(out, " adds ")
                attackSeg(out, "${entry.damage}")
                whiteSeg(out, " elemental damage to ${entry.target}")
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
            is CombatLogEntry.HealCast -> {
                whiteSeg(out, entry.caster)
                whiteSeg(out, " ")
                healSeg(out, "casts")
                whiteSeg(out, " ${entry.spellName} on ${entry.target} ")
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
            is CombatLogEntry.UtilitySkillUsed -> {
                whiteSeg(out, entry.caster)
                whiteSeg(out, " uses ")
                healSeg(out, entry.skillName)
                whiteSeg(out, ".")
            }
            is CombatLogEntry.UtilityRecoveryTotals -> {
                val parts = mutableListOf<String>()
                if (entry.totalHp > 0) parts += "+${entry.totalHp} HP"
                if (entry.totalMp > 0) parts += "+${entry.totalMp} MP"
                if (parts.isEmpty()) {
                    whiteSeg(out, "No HP or MP recovered.")
                } else {
                    healSeg(out, parts.joinToString(", "))
                    whiteSeg(out, " recovered.")
                }
            }
            is CombatLogEntry.ItemGained -> {
                whiteSeg(out, "Gained ")
                advantageSeg(out, entry.itemName)
                whiteSeg(out, " (added to inventory).")
            }
        }
    }

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

    private fun xpSeg(out: SpannableStringBuilder, text: String) {
        appendSpan(out, text, COLOR_XP, bold = true)
    }

    private fun healSeg(out: SpannableStringBuilder, text: String) {
        appendSpan(out, text, COLOR_HEAL, bold = false)
    }

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
        private val COLOR_BACKDROP: Int = Color.argb(0x90, 0x00, 0x00, 0x00)
        private val COLOR_TEXT: Int = Color.parseColor("#FFF5ECD2")
        private val COLOR_ATTACK: Int = Color.parseColor("#FFE54D4D")
        private val COLOR_CRIT: Int = Color.parseColor("#FFFF7676")
        private val COLOR_ADVANTAGE: Int = Color.parseColor("#FFFFB347")
        private val COLOR_DISADVANTAGE: Int = Color.parseColor("#FF7FBFFF")
        private val COLOR_XP: Int = Color.parseColor("#FFE6C66B")
        private val COLOR_LEVEL_UP: Int = Color.parseColor("#FFFFD24F")
        private val COLOR_HEAL: Int = Color.parseColor("#FF7FD17F")
    }
}
