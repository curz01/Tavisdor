package com.tavisdor.app.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.AnimationDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.tavisdor.app.R
import com.tavisdor.app.game.Game
import com.tavisdor.app.party.Hero
import com.tavisdor.app.skills.Skill
import com.tavisdor.app.skills.SkillButton
import com.tavisdor.app.skills.SkillCastType
import com.tavisdor.app.skills.SkillCatalog

/**
 * Modal skill picker launched from the bottom hero panel's ACT / GRD
 * buttons.
 *
 * Unlike a vanilla [AlertDialog.Builder.setItems] list, tapping a row
 * here does NOT immediately fire the skill. Instead the chosen [Skill]
 * is staged on [Game.setSelectedSkill] for the hero in [slot]; the
 * row picks up a blinking border to make the selection obvious:
 *
 *   - Green blink when [Skill.costsAction] is true (normal skill).
 *   - Yellow blink when [Skill.costsAction] is false (free-action skill).
 *
 * The actual "fire the staged skill" trigger is the top-level Action
 * button that will be added when the combat system lands. Tapping the
 * already-selected row toggles the selection off. Tapping a passive
 * skill is a no-op (passives are always active and never staged).
 *
 * Construct fresh on every open; selection persists in [Game], so
 * reopening the same hero / button shows the previous pick already lit.
 */
class SkillPickerDialog(
    private val context: Context,
    private val game: Game,
) {

    /** Rows keyed by [Skill.id] so we can quickly restyle on selection swap. */
    private val rowsBySkillId: MutableMap<String, View> = mutableMapOf()
    private var currentSelectionId: String? = null
    private var slot: Int = -1

    fun show(slot: Int, hero: Hero, button: SkillButton) {
        this.slot = slot
        this.currentSelectionId = game.selectedSkillFor(slot)?.id
        this.rowsBySkillId.clear()

        val skills: List<Skill> = hero.knownSkillsFor(button)
        val title = context.getString(titleResFor(button), hero.name)

        if (skills.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(R.string.hero_skill_popup_empty)
                .setPositiveButton(R.string.hero_skill_popup_close) { d, _ -> d.dismiss() }
                .show()
            return
        }

        val content = buildContentView(skills)
        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(content)
            .setNegativeButton(R.string.hero_skill_popup_close) { d, _ -> d.dismiss() }
            .setOnDismissListener {
                // Stop any blinking animation drawables so they don't keep
                // ticking in the background after the window's gone.
                stopAllBlinks()
                rowsBySkillId.clear()
            }
            .show()

        // Restart the blink on the currently-selected row AFTER the
        // dialog is attached - AnimationDrawable.start() before the
        // window is shown silently no-ops on some devices.
        currentSelectionId?.let { id ->
            rowsBySkillId[id]?.post { startBlink(id) }
        }
    }

    // ---- View construction ----

    private fun buildContentView(skills: List<Skill>): View {
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            setPadding(dp(12), dp(4), dp(12), dp(4))
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        skills.forEach { skill ->
            val row = buildSkillRow(skill)
            rowsBySkillId[skill.id] = row
            column.addView(row)
        }
        scroll.addView(column)
        return scroll
    }

    /**
     * Single skill row. Outer container exists separately from the inner
     * `body` so the blinking AnimationDrawable can sit on a view whose
     * size doesn't change with content, and so toggling the border never
     * forces a relayout of the text inside.
     */
    private fun buildSkillRow(skill: Skill): View {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // The blink frames already include their own corners + stroke;
            // padding here is what keeps the text from kissing the border.
            setPadding(dp(8), dp(6), dp(8), dp(6))
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = dp(2)
            lp.bottomMargin = dp(2)
            layoutParams = lp
            isClickable = true
            isFocusable = true
            // Material ripple touch feedback even when no background is set.
            val outValue = TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground, outValue, true
            )
            foreground = AppCompatResources.getDrawable(context, outValue.resourceId)
        }

        val nameLine = TextView(context).apply {
            text = formatNameLine(skill)
            setTextColor(context.getColor(R.color.skill_picker_row_name))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
        }

        outer.addView(nameLine)

        if (skill.description.isNotBlank()) {
            val desc = TextView(context).apply {
                text = skill.description
                setTextColor(context.getColor(R.color.skill_picker_row_desc))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                lp.topMargin = dp(2)
                layoutParams = lp
            }
            outer.addView(desc)
        }

        outer.setOnClickListener { onRowTapped(skill, outer) }

        // Initial visual state: if Game already has this skill staged,
        // light up the border now (we'll start() the animation after the
        // dialog attaches, see show()).
        if (skill.id == currentSelectionId) {
            applySelectionBackground(outer, skill)
        }

        return outer
    }

    private fun formatNameLine(skill: Skill): CharSequence {
        val sb = StringBuilder()
        sb.append(skill.displayName)
        sb.append(context.getString(R.string.hero_skill_range_suffix, skill.range))
        if (skill.mpCost > 0) {
            sb.append("   ").append(skill.mpCost).append(" MP")
        }
        when {
            skill.castType == SkillCastType.PASSIVE ->
                sb.append("   [").append(context.getString(R.string.hero_skill_passive_tag)).append("]")
            !skill.costsAction ->
                sb.append("   [").append(context.getString(R.string.skill_picker_free_tag)).append("]")
        }
        return sb.toString()
    }

    // ---- Selection logic ----

    private fun onRowTapped(skill: Skill, row: View) {
        // Passive skills can't be staged - the row's "passive" tag
        // and the lack of selection feedback communicate that on
        // its own; no toast needed.
        if (skill.castType == SkillCastType.PASSIVE) return

        // Tapping the already-selected row reverts to the default
        // basic Attack (never to "nothing staged"). Tapping Attack
        // while Attack is already selected is a no-op.
        if (skill.id == currentSelectionId) {
            if (skill.id == SkillCatalog.BASIC_ATTACK_ID) return
            revertToDefault()
            return
        }

        // Otherwise: clear the previously selected row's border and
        // stage the new skill. The new row's blinking border IS the
        // feedback - no toast needed.
        currentSelectionId?.let { oldId ->
            rowsBySkillId[oldId]?.let { clearSelectionBackground(it) }
        }
        currentSelectionId = skill.id
        applySelectionBackground(row, skill)
        startBlink(skill.id)
        game.setSelectedSkill(slot, skill)
    }

    /**
     * Snaps the selection back to the hero's basic Attack. If the
     * Attack row exists in this picker (only true when [SkillButton.ACTION]
     * is showing) it picks up the green blink immediately; otherwise
     * the dialog just goes quiet and the panel's white border tracks
     * the underlying [Game] state which is now Attack-staged again.
     */
    private fun revertToDefault() {
        // Remove the current row's border so the dialog reads as
        // "nothing in this picker is staged right now".
        currentSelectionId?.let { oldId ->
            rowsBySkillId[oldId]?.let { clearSelectionBackground(it) }
        }
        // Passing null asks Game to fall back to the slot's default
        // (basic Attack). We then read it back so the picker's local
        // state stays in lock-step with the game state.
        game.setSelectedSkill(slot, null)
        val newDefault = game.selectedSkillFor(slot)
        currentSelectionId = newDefault?.id

        // If we're currently looking at the ACT bucket, the Attack
        // row lives in this dialog - light it up so the player sees
        // exactly what's staged now. That blink IS the feedback;
        // no toast needed.
        if (newDefault != null) {
            rowsBySkillId[newDefault.id]?.let { row ->
                applySelectionBackground(row, newDefault)
                row.post { startBlink(newDefault.id) }
            }
        }
    }

    // ---- Background swap helpers ----

    private fun applySelectionBackground(row: View, skill: Skill) {
        val res = if (skill.costsAction) {
            R.drawable.skill_select_border_green_blink
        } else {
            R.drawable.skill_select_border_yellow_blink
        }
        row.setBackgroundResource(res)
    }

    private fun clearSelectionBackground(row: View) {
        (row.background as? AnimationDrawable)?.stop()
        row.background = null
    }

    private fun startBlink(skillId: String) {
        val row = rowsBySkillId[skillId] ?: return
        (row.background as? AnimationDrawable)?.let {
            // Some Android versions don't auto-start a freshly attached
            // AnimationDrawable until the view has been laid out.
            it.stop()
            it.start()
        }
    }

    private fun stopAllBlinks() {
        rowsBySkillId.values.forEach { row ->
            (row.background as? AnimationDrawable)?.stop()
        }
    }

    // ---- Misc helpers ----

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun titleResFor(button: SkillButton): Int = when (button) {
        SkillButton.ACTION -> R.string.hero_skill_popup_title_action
        SkillButton.GUARD -> R.string.hero_skill_popup_title_guard
    }
}
