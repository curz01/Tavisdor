package com.tavisdor.app.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.button.MaterialButton
import com.tavisdor.app.R
import com.tavisdor.app.game.Game
import com.tavisdor.app.party.Hero
import com.tavisdor.app.party.HeroClass
import com.tavisdor.app.party.defensiveSkillsForAssign
import com.tavisdor.app.party.offensiveSkillsForAssign
import com.tavisdor.app.party.passiveSkillsForAssign
import com.tavisdor.app.skills.Skill
import com.tavisdor.app.skills.SkillCastType

/**
 * Modal panel for staging which skill the top Action button will commit
 * for a hero. Layout mirrors the design wireframe: offensive column on
 * the left, defensive + passive stacked on the right. Each skill shows
 * its name with range / MP cost on lines below; tapping a row stages or
 * unstages it immediately.
 *
 * A hero may stage one main-action skill (green border) and one optional
 * [NA] free-action skill (yellow border) at the same time. Confirm or
 * the upper-right close control commits the staging and dismisses the
 * panel (with a reminder dialog if only a free-action is staged).
 */
class HeroSkillAssignScreen(
    private val root: ViewGroup,
    private val game: Game,
    var onDismiss: (() -> Unit)? = null,
    var onSelectionChanged: (() -> Unit)? = null,
) {
    private val panelHost: View = root.findViewById(R.id.heroSkillAssignPanelHost)
    private val tvHeader: TextView = root.findViewById(R.id.tvHeroSkillAssignHeader)
    private val tvMana: TextView = root.findViewById(R.id.tvHeroSkillAssignMana)
    private val btnClose: MaterialButton = root.findViewById(R.id.btnHeroSkillAssignClose)
    private val btnConfirm: MaterialButton = root.findViewById(R.id.btnHeroSkillAssignConfirm)
    private val listOffensive: LinearLayout = root.findViewById(R.id.heroSkillAssignOffensiveList)
    private val listDefensive: LinearLayout = root.findViewById(R.id.heroSkillAssignDefensiveList)
    private val listPassive: LinearLayout = root.findViewById(R.id.heroSkillAssignPassiveList)

    private val ctx: Context get() = root.context
    private val rowsBySkillId: MutableMap<String, View> = mutableMapOf()
    private var slot: Int = -1
    private var hero: Hero? = null

    init {
        panelHost.setOnClickListener { /* consume */ }
        btnClose.setOnClickListener { commitAndDismiss() }
        btnConfirm.setOnClickListener { commitAndDismiss() }
    }

    val isVisible: Boolean get() = root.visibility == View.VISIBLE

    fun show(slot: Int, hero: Hero) {
        this.slot = slot
        this.hero = hero
        rowsBySkillId.clear()

        tvHeader.text = ctx.getString(
            R.string.hero_detail_header_format,
            hero.name,
            classDisplayName(hero.heroClass),
        )
        tvMana.text = ctx.getString(
            R.string.hero_skill_assign_mana_format,
            hero.mp,
            hero.maxMp,
        )

        populateList(listOffensive, hero.offensiveSkillsForAssign()) { true }
        populateList(listDefensive, hero.defensiveSkillsForAssign()) { true }
        populateList(listPassive, hero.passiveSkillsForAssign()) { !it.isPassive }

        refreshSelectionHighlight()
        root.visibility = View.VISIBLE
    }

    fun hide() {
        root.visibility = View.GONE
        onDismiss?.invoke()
    }

    /** Commits staged skills and closes the panel (used by Confirm, X, and back). */
    fun commitAndDismiss() {
        if (needsMainActionReminder()) {
            showMainActionReminderDialog()
            return
        }
        hide()
    }

    private fun needsMainActionReminder(): Boolean =
        game.selectedFreeActionSkillFor(slot) != null && !game.hasExplicitMainStaged(slot)

    private fun showMainActionReminderDialog() {
        AlertDialog.Builder(ctx)
            .setMessage(R.string.hero_skill_assign_need_main_action)
            .setPositiveButton(R.string.hero_skill_assign_passive_ok) { _, _ -> hide() }
            .show()
    }

    private fun populateList(
        container: LinearLayout,
        skills: List<Skill>,
        assignable: (Skill) -> Boolean,
    ) {
        container.removeAllViews()
        if (skills.isEmpty()) {
            container.addView(buildEmptyRow())
            return
        }
        skills.forEach { skill ->
            val canAssign = assignable(skill)
            val row = buildSkillNameRow(skill, canAssign)
            rowsBySkillId[skill.id] = row
            container.addView(row)
        }
    }

    private fun buildEmptyRow(): TextView = TextView(ctx).apply {
        text = ctx.getString(R.string.hero_skill_assign_section_empty)
        setTextColor(ctx.getColor(R.color.skill_picker_row_meta))
        setTextSize(TypedValue.COMPLEX_UNIT_PX, dimenPx(R.dimen.hero_skill_assign_meta_text))
        layoutParams = rowLayoutParams()
    }

    private fun buildSkillNameRow(skill: Skill, assignable: Boolean): View {
        val padH = dimenPx(R.dimen.hero_skill_assign_row_pad_h).toInt()
        val padV = dimenPx(R.dimen.hero_skill_assign_row_pad_v).toInt()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, padV)
            layoutParams = rowLayoutParams()
            isClickable = true
            isFocusable = true
            val outValue = TypedValue()
            ctx.theme.resolveAttribute(
                android.R.attr.selectableItemBackground, outValue, true,
            )
            foreground = AppCompatResources.getDrawable(ctx, outValue.resourceId)
            setOnClickListener { onSkillTapped(skill, assignable) }
        }

        row.addView(metaTextView(skill.displayName, bold = true, nameColor = true))
        row.addView(metaTextView(formatStatsLine(skill)))

        formatTagLine(skill)?.let { tag ->
            row.addView(metaTextView(tag))
        }

        return row
    }

    private fun rowLayoutParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dimenPx(R.dimen.hero_skill_assign_row_gap).toInt()
        }

    private fun metaTextView(
        text: CharSequence,
        bold: Boolean = false,
        nameColor: Boolean = false,
    ): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(
            ctx.getColor(
                if (nameColor) R.color.skill_picker_row_name
                else R.color.skill_picker_row_meta,
            ),
        )
        setTextSize(
            TypedValue.COMPLEX_UNIT_PX,
            dimenPx(
                if (nameColor) R.dimen.hero_skill_assign_name_text
                else R.dimen.hero_skill_assign_meta_text,
            ),
        )
        if (bold) setTypeface(typeface, Typeface.BOLD)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        if (!nameColor) lp.topMargin = dp(2)
        layoutParams = lp
    }

    private fun formatStatsLine(skill: Skill): String {
        val parts = buildList {
            if (skill.mpCost > 0) {
                add(ctx.getString(R.string.hero_skill_assign_mp_part, skill.mpCost))
            }
            if (skill.requiredShard != null) {
                add(ctx.getString(R.string.hero_skill_assign_range_req_shard, skill.range))
            } else {
                add(ctx.getString(R.string.hero_skill_assign_range_part, skill.range))
            }
            if (!skill.costsAction) {
                add(ctx.getString(R.string.hero_skill_assign_no_action_part))
            }
        }
        return parts.joinToString(" - ")
    }

    private fun hasRequiredShard(skill: Skill): Boolean {
        val shard = skill.requiredShard ?: return true
        return game.party?.inventory?.hasIngredient(shard) == true
    }

    private fun formatTagLine(skill: Skill): String? =
        if (skill.castType == SkillCastType.PASSIVE) {
            "[${ctx.getString(R.string.hero_skill_passive_tag)}]"
        } else {
            null
        }

    private fun onSkillTapped(skill: Skill, assignable: Boolean) {
        if (!assignable) {
            showPassiveInfoDialog(skill)
            return
        }
        if (game.isSkillStaged(slot, skill)) {
            game.unstageSkill(slot, skill)
            refreshSelectionHighlight()
            onSelectionChanged?.invoke()
            return
        }
        if (!hasRequiredShard(skill)) {
            val shard = skill.requiredShard ?: return
            AppToast.show(
                ctx,
                ctx.getString(R.string.hero_skill_assign_missing_shard, shard.displayName),
            )
            return
        }
        if (!game.canAffordStagedSkills(slot, skill)) {
            AppToast.show(ctx, ctx.getString(R.string.hero_skill_assign_insufficient_mp))
            return
        }
        if (!game.stageSkill(slot, skill)) {
            AppToast.show(ctx, ctx.getString(R.string.hero_skill_assign_insufficient_mp))
            return
        }
        refreshSelectionHighlight()
        onSelectionChanged?.invoke()
    }

    private fun showPassiveInfoDialog(skill: Skill) {
        val body = buildString {
            if (skill.description.isNotBlank()) {
                append(skill.description)
                append("\n\n")
            }
            append(ctx.getString(R.string.skill_picker_passive_locked))
        }
        AlertDialog.Builder(ctx)
            .setTitle(ctx.getString(R.string.hero_skill_assign_confirm_title, skill.displayName))
            .setMessage(body)
            .setPositiveButton(R.string.hero_skill_assign_passive_ok, null)
            .show()
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

    private fun dimenPx(res: Int): Float = ctx.resources.getDimension(res)

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
        (value * ctx.resources.displayMetrics.density).toInt()

    private fun classDisplayName(heroClass: HeroClass): String = ctx.getString(
        when (heroClass) {
            HeroClass.MAGE -> R.string.class_mage
            HeroClass.THIEF -> R.string.class_thief
            HeroClass.FIGHTER -> R.string.class_fighter
            HeroClass.ARCHER -> R.string.class_archer
        },
    )
}
