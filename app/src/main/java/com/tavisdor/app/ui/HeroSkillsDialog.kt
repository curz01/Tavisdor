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
import com.tavisdor.app.R
import com.tavisdor.app.party.Hero
import com.tavisdor.app.party.HeroClass
import com.tavisdor.app.skills.Skill
import com.tavisdor.app.skills.SkillButton

/**
 * Read-only viewer for a hero's full skill / spell list.
 *
 * Opened from the "Skills & Spells" button on the hero detail panel.
 * Lists all known skills (everything with unlockLevel <= hero level)
 * grouped into two sections in deterministic order:
 *   - Active   (SkillButton.ACTION) - damage skills and spells
 *   - Reactive (SkillButton.GUARD)  - prepared / passive / setup skills
 *
 * The standalone Spells section was retired when spells were folded
 * into Actions; the SPL bucket is gone from [SkillButton] entirely.
 *
 * No selection / staging happens here - that flow lives in
 * [SkillPickerDialog]. This dialog is purely informational. Close
 * dismisses via the dialog's own close button.
 */
class HeroSkillsDialog(private val context: Context) {

    private val density: Float = context.resources.displayMetrics.density

    fun show(hero: Hero) {
        val title = context.getString(
            R.string.hero_detail_skills_dialog_title,
            hero.name,
        )

        val scroll = ScrollView(context).apply {
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        scroll.addView(column)
        populate(column, hero)

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(R.string.hero_detail_close) { d, _ -> d.dismiss() }
            .show()
    }

    /** Mirrors the section ordering the inline panel previously used. */
    private fun populate(container: LinearLayout, hero: Hero) {
        val all = hero.knownSkills
        if (all.isEmpty()) {
            container.addView(emptyRow(R.string.hero_detail_skills_none))
            return
        }
        val grouped = all.groupBy { it.button }
        listOf(
            SkillButton.ACTION to R.string.hero_detail_skills_section_action,
            SkillButton.GUARD to R.string.hero_detail_skills_section_guard,
        ).forEach { (bucket, headerRes) ->
            val list = grouped[bucket].orEmpty()
            if (list.isEmpty()) return@forEach
            container.addView(sectionHeader(headerRes))
            list.forEach { skill -> container.addView(skillRow(skill)) }
        }
    }

    private fun sectionHeader(stringRes: Int): TextView = TextView(context).apply {
        text = context.getString(stringRes)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(context.getColor(R.color.hero_detail_label_dim))
        letterSpacing = 0.10f
        isAllCaps = true
        setTypeface(typeface, Typeface.BOLD)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(10) }
    }

    private fun skillRow(skill: Skill): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) }
        }
        val title = TextView(context).apply {
            text = formatSkillHeader(skill)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(context.getColor(R.color.hero_detail_value))
            setTypeface(typeface, Typeface.BOLD)
        }
        row.addView(title)
        if (skill.description.isNotBlank()) {
            val description = TextView(context).apply {
                text = skill.description
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(context.getColor(R.color.hero_detail_label_dim))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(2) }
            }
            row.addView(description)
        }
        return row
    }

    private fun emptyRow(stringRes: Int): TextView = TextView(context).apply {
        text = context.getString(stringRes)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextColor(context.getColor(R.color.hero_detail_slot_empty))
        setTypeface(typeface, Typeface.ITALIC)
    }

    /**
     * Leading line of a skill row, e.g.
     *   "Fire I   Range: 1   1 MP"
     *   "Cover   Range: 1   [No action]"
     *   "Cover   Range: 1   [No action]"
     * [No action] when the skill does not consume the hero's turn.
     * MP cost appended when nonzero.
     */
    private fun formatSkillHeader(skill: Skill): String {
        val sb = StringBuilder()
        sb.append(context.getString(R.string.hero_skill_row_format, skill.displayName, skill.range))
        if (skill.mpCost > 0) {
            sb.append(context.getString(R.string.hero_skill_row_mp_suffix, skill.mpCost))
        }
        if (!skill.costsAction) {
            sb.append("  [").append(context.getString(R.string.skill_picker_free_tag)).append("]")
        }
        return sb.toString()
    }

    /** Unused for now but kept for symmetry with HeroDetailScreen. */
    @Suppress("unused")
    private fun classDisplayName(cls: HeroClass): String =
        cls.name.lowercase().replaceFirstChar { it.uppercaseChar() }

    private fun dp(v: Int): Int = (v * density).toInt()
}
