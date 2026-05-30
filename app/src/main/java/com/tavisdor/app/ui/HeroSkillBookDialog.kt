package com.tavisdor.app.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.tavisdor.app.R
import com.tavisdor.app.party.Hero
import com.tavisdor.app.skills.Skill
import com.tavisdor.app.skills.SkillButton
import com.tavisdor.app.skills.SkillMaterialText

/**
 * Read-only skill reference for one hero (opened from the skill-assign panel
 * skillbook button). Shows full description, mana, range, and material needs.
 */
class HeroSkillBookDialog(private val context: Context) {

    private val inflater: LayoutInflater = LayoutInflater.from(context)

    fun show(hero: Hero) {
        val root = inflater.inflate(R.layout.dialog_hero_skill_book, null, false)
        root.findViewById<TextView>(R.id.tvSkillBookTitle).text =
            context.getString(R.string.skillbook_dialog_title, hero.name)
        val list = root.findViewById<LinearLayout>(R.id.skillBookSkillList)
        populate(list, hero)

        val dialog = AlertDialog.Builder(context)
            .setView(root)
            .create()
        root.findViewById<MaterialButton>(R.id.btnSkillBookClose)
            .setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

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
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dimenPx(R.dimen.hero_skill_book_section_text))
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
        row.addView(metaLine(skill.displayName, bold = true, dim = false))
        row.addView(metaLine(formatMana(skill), bold = false, dim = true))
        row.addView(metaLine(formatRange(skill), bold = false, dim = true))
        SkillMaterialText.format(context, skill)?.let { material ->
            row.addView(metaLine(material, bold = false, dim = true))
        }
        formatTags(skill)?.let { tags ->
            row.addView(metaLine(tags, bold = false, dim = true))
        }
        if (skill.description.isNotBlank()) {
            row.addView(
                metaLine(skill.description, bold = false, dim = true).apply {
                    setPadding(paddingLeft, dp(4), paddingRight, paddingBottom)
                },
            )
        }
        return row
    }

    private fun formatMana(skill: Skill): String =
        if (skill.mpCost > 0) {
            context.getString(R.string.skillbook_mana_cost, skill.mpCost)
        } else {
            context.getString(R.string.skillbook_mana_free)
        }

    private fun formatRange(skill: Skill): String =
        context.getString(R.string.skillbook_range, skill.range)

    private fun formatTags(skill: Skill): String? {
        if (skill.costsAction) return null
        return context.getString(R.string.skill_picker_free_tag)
    }

    private fun metaLine(text: CharSequence, bold: Boolean, dim: Boolean): TextView =
        TextView(context).apply {
            this.text = text
            setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                dimenPx(
                    if (bold) R.dimen.hero_skill_book_title_text
                    else R.dimen.hero_skill_book_body_text,
                ),
            )
            setTextColor(
                context.getColor(
                    if (dim) R.color.hero_detail_label_dim else R.color.hero_detail_value,
                ),
            )
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun emptyRow(stringRes: Int): TextView = TextView(context).apply {
        text = context.getString(stringRes)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dimenPx(R.dimen.hero_skill_book_body_text))
        setTextColor(context.getColor(R.color.hero_detail_slot_empty))
        setTypeface(typeface, Typeface.ITALIC)
    }

    private fun dimenPx(resId: Int): Float =
        context.resources.getDimension(resId)

    private fun dp(v: Int): Int =
        (v * context.resources.displayMetrics.density).toInt()
}
