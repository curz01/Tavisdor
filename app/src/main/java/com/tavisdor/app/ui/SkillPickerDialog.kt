package com.tavisdor.app.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
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
 * Tapping a row stages the skill without firing it. Main-action skills
 * get a green border; [NA] free-action skills get a yellow border. Both
 * can be staged at once.
 */
class SkillPickerDialog(
    private val context: Context,
    private val game: Game,
) {

    private val rowsBySkillId: MutableMap<String, View> = mutableMapOf()
    private var slot: Int = -1
    private var hero: Hero? = null

    fun show(slot: Int, hero: Hero, button: SkillButton) {
        this.slot = slot
        this.hero = hero
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
                rowsBySkillId.clear()
            }
            .show()

        refreshSelectionHighlight()
    }

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

    private fun buildSkillRow(skill: Skill): View {
        val outer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
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
            val outValue = TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackground, outValue, true,
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

    private fun onRowTapped(skill: Skill, row: View) {
        if (skill.castType == SkillCastType.PASSIVE) return

        if (game.isSkillStaged(slot, skill)) {
            if (skill.id == SkillCatalog.BASIC_ATTACK_ID && skill.costsAction) return
            game.unstageSkill(slot, skill)
            refreshSelectionHighlight()
            return
        }

        if (!game.canAffordStagedSkills(slot, skill)) {
            AppToast.show(context, context.getString(R.string.hero_skill_assign_insufficient_mp))
            return
        }

        if (!game.stageSkill(slot, skill)) {
            AppToast.show(context, context.getString(R.string.hero_skill_assign_insufficient_mp))
            return
        }

        if (!skill.costsAction && !game.hasExplicitMainStaged(slot)) {
            AppToast.show(
                context,
                context.getString(R.string.hero_skill_assign_need_main_action),
                duration = android.widget.Toast.LENGTH_LONG,
            )
        }

        refreshSelectionHighlight()
    }

    private fun refreshSelectionHighlight() {
        val h = hero ?: game.party?.heroes?.getOrNull(slot) ?: return
        val mainSkill = game.stagedMainSkillOrNull(slot) ?: h.basicAttackSkill
        val freeId = game.selectedFreeActionSkillFor(slot)?.id

        rowsBySkillId.values.forEach { clearSelectionBackground(it) }

        rowsBySkillId[mainSkill.id]?.let { row ->
            applySelectionBackground(row, mainSkill)
        }
        freeId?.let { id ->
            val freeSkill = h.knownSkills.find { it.id == id } ?: return@let
            rowsBySkillId[id]?.let { row ->
                applySelectionBackground(row, freeSkill)
            }
        }
    }

    private fun applySelectionBackground(row: View, skill: Skill) {
        val res = if (skill.costsAction) {
            R.drawable.skill_select_border_green_on
        } else {
            R.drawable.skill_select_border_yellow_on
        }
        row.setBackgroundResource(res)
    }

    private fun clearSelectionBackground(row: View) {
        row.background = null
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun titleResFor(button: SkillButton): Int = when (button) {
        SkillButton.ACTION -> R.string.hero_skill_popup_title_action
        SkillButton.GUARD -> R.string.hero_skill_popup_title_guard
    }
}
