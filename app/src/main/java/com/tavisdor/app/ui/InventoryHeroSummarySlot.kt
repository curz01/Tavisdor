package com.tavisdor.app.ui

import android.view.View
import android.widget.TextView
import com.tavisdor.app.R
import com.tavisdor.app.party.Hero
import com.tavisdor.app.party.HeroClass

/**
 * One hero card in the inventory overlay's 2x2 party summary grid.
 * Slot index matches [com.tavisdor.app.party.Party] ordering (0–1 front,
 * 2–3 back) and the bottom [com.tavisdor.app.HeroPanelView] layout.
 */
class InventoryHeroSummarySlot(
    val root: View,
) {
    private val header: TextView = root.findViewById(R.id.tvHeroSummaryHeader)
    private val tvLevel: TextView = root.findViewById(R.id.tvHeroSummaryLevel)
    private val tvHp: TextView = root.findViewById(R.id.tvHeroSummaryHp)
    private val tvMp: TextView = root.findViewById(R.id.tvHeroSummaryMp)
    private val tvAc: TextView = root.findViewById(R.id.tvHeroSummaryAc)
    private val tvStr: TextView = root.findViewById(R.id.tvHeroSummaryStr)
    private val tvDex: TextView = root.findViewById(R.id.tvHeroSummaryDex)
    private val tvInt: TextView = root.findViewById(R.id.tvHeroSummaryInt)
    private val tvXp: TextView = root.findViewById(R.id.tvHeroSummaryXp)

    private val ctx get() = root.context

    fun bind(hero: Hero) {
        val classLabel = ctx.getString(classNameRes(hero.heroClass))
        header.text = ctx.getString(R.string.hero_detail_header_format, hero.name, classLabel)
        tvLevel.text = ctx.getString(R.string.inventory_summary_level, hero.level)

        tvHp.text = ctx.getString(R.string.inventory_summary_hp, hero.hp, hero.maxHp)
        tvMp.text = ctx.getString(R.string.inventory_summary_mp, hero.mp, hero.maxMp)
        tvAc.text = ctx.getString(R.string.inventory_summary_ac, hero.armorClass)
        tvStr.text = ctx.getString(R.string.inventory_summary_str, hero.strength)
        tvDex.text = ctx.getString(R.string.inventory_summary_dex, hero.dexterity)
        tvInt.text = ctx.getString(R.string.inventory_summary_int, hero.intelligence)
        tvXp.text = ctx.getString(
            R.string.inventory_summary_exp,
            hero.xp,
            hero.xpForNextLevel,
        )
    }

    private fun classNameRes(cls: HeroClass): Int = when (cls) {
        HeroClass.MAGE -> R.string.class_mage
        HeroClass.THIEF -> R.string.class_thief
        HeroClass.FIGHTER -> R.string.class_fighter
        HeroClass.ARCHER -> R.string.class_archer
    }
}
