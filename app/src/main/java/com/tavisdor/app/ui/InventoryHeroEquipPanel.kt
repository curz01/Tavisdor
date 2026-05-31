package com.tavisdor.app.ui

import android.content.Context
import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.tavisdor.app.R
import com.tavisdor.app.party.Hero
import com.tavisdor.app.party.HeroClass

/**
 * Per-hero equipment view inside the inventory overlay. Replaces the
 * 2x2 party summary when a hero card is tapped.
 */
class InventoryHeroEquipPanel(
    private val root: View,
    var onClose: (() -> Unit)? = null,
    var onPrevHero: (() -> Unit)? = null,
    var onNextHero: (() -> Unit)? = null,
) {
    enum class EquipSlot {
        HELMET,
        ARMOR,
        PRIMARY_WEAPON,
        OFF_HAND_WEAPON,
        BOOTS,
    }

    var onSlotClick: ((EquipSlot) -> Unit)? = null

    private val ctx: Context get() = root.context

    private val tvHeader: TextView = root.findViewById(R.id.tvInventoryHeroEquipHeader)
    private val btnClose: MaterialButton = root.findViewById(R.id.btnInventoryHeroEquipClose)
    private val btnPrev: MaterialButton = root.findViewById(R.id.btnInventoryHeroEquipPrev)
    private val btnNext: MaterialButton = root.findViewById(R.id.btnInventoryHeroEquipNext)

    private val slotHelmet: TextView = root.findViewById(R.id.inventoryEquipSlotHelmet)
    private val slotArmor: TextView = root.findViewById(R.id.inventoryEquipSlotArmor)
    private val slotWeapon1: TextView = root.findViewById(R.id.inventoryEquipSlotWeapon1)
    private val slotWeapon2: TextView = root.findViewById(R.id.inventoryEquipSlotWeapon2)
    private val slotBoots: TextView = root.findViewById(R.id.inventoryEquipSlotBoots)

    init {
        root.setOnClickListener { /* consume so taps don't dismiss the overlay */ }
        btnClose.setOnClickListener { onClose?.invoke() }
        btnPrev.setOnClickListener { onPrevHero?.invoke() }
        btnNext.setOnClickListener { onNextHero?.invoke() }

        slotHelmet.setOnClickListener { onSlotClick?.invoke(EquipSlot.HELMET) }
        slotArmor.setOnClickListener { onSlotClick?.invoke(EquipSlot.ARMOR) }
        slotWeapon1.setOnClickListener { onSlotClick?.invoke(EquipSlot.PRIMARY_WEAPON) }
        slotWeapon2.setOnClickListener { onSlotClick?.invoke(EquipSlot.OFF_HAND_WEAPON) }
        slotBoots.setOnClickListener { onSlotClick?.invoke(EquipSlot.BOOTS) }

        listOf(slotHelmet, slotArmor, slotWeapon1, slotWeapon2, slotBoots).forEach { slot ->
            slot.isClickable = true
            slot.isFocusable = true
        }
    }

    fun bind(hero: Hero) {
        tvHeader.text = ctx.getString(
            R.string.hero_detail_header_format,
            hero.name,
            ctx.getString(classNameRes(hero.heroClass)),
        )
        bindSlot(slotHelmet, R.string.hero_detail_slot_helmet, hero.helmet)
        bindSlot(slotArmor, R.string.hero_detail_slot_armor, hero.armor?.displayName)
        bindSlot(slotWeapon1, R.string.hero_detail_slot_weapon1, hero.weapon1?.displayName)
        bindSlot(slotWeapon2, R.string.hero_detail_slot_weapon2, hero.weapon2?.displayName)
        bindSlot(slotBoots, R.string.hero_detail_slot_boots, hero.boots)
    }

    fun show() {
        root.visibility = View.VISIBLE
    }

    fun hide() {
        root.visibility = View.GONE
    }

    private fun bindSlot(view: TextView, labelRes: Int, item: String?) {
        val label = ctx.getString(labelRes)
        val secondLine = item ?: ctx.getString(R.string.hero_detail_slot_empty)
        view.text = "$label\n$secondLine"
    }

    private fun classNameRes(cls: HeroClass): Int = when (cls) {
        HeroClass.MAGE -> R.string.class_mage
        HeroClass.THIEF -> R.string.class_thief
        HeroClass.FIGHTER -> R.string.class_fighter
        HeroClass.ARCHER -> R.string.class_archer
    }
}
