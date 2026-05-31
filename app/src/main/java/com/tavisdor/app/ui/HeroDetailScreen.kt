package com.tavisdor.app.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.tavisdor.app.R
import com.tavisdor.app.party.Hero
import com.tavisdor.app.party.HeroClass

/**
 * Controller for the modal hero detail / equipment panel.
 *
 * Layout summary (see `R.layout.activity_main` `heroDetailOverlay`):
 *   - Outer FrameLayout is the scrim; tapping it dismisses the overlay.
 *   - Inner ScrollView holds a ConstraintLayout panel that intercepts
 *     touches so taps inside the panel don't bubble out to the scrim.
 *   - Header (centered)            : "Name - Class"
 *   - Upper left                   : Level / HP / MP
 *   - Upper right                  : XP current / next-level
 *   - Equipment grid (3x3 cross)   : Helmet / Weapon1 / Armor / Weapon2 / Boots
 *   - Lower left                   : STR / DEX / INT
 *   - Lower right                  : AC
 *   - "Skills & Spells" button     : opens [HeroSkillsDialog]
 *   - Bottom                       : Close button
 *
 * Use [show] / [hide] to toggle; [onDismiss] is fired whenever the panel
 * closes (button, outside-tap, or programmatic) so MainActivity can clear
 * any state it tracks.
 */
class HeroDetailScreen(
    private val root: ViewGroup,
    var onDismiss: (() -> Unit)? = null,
) {
    private val scroll: ScrollView = root.findViewById(R.id.heroDetailScroll)
    private val panel: View = root.findViewById(R.id.heroDetailPanel)

    private val tvHeader: TextView = root.findViewById(R.id.tvHeroDetailHeader)
    private val tvLevel: TextView = root.findViewById(R.id.tvHeroDetailLevel)
    private val tvHp: TextView = root.findViewById(R.id.tvHeroDetailHp)
    private val tvMp: TextView = root.findViewById(R.id.tvHeroDetailMp)
    private val tvXp: TextView = root.findViewById(R.id.tvHeroDetailXp)
    private val tvStr: TextView = root.findViewById(R.id.tvHeroDetailStr)
    private val tvDex: TextView = root.findViewById(R.id.tvHeroDetailDex)
    private val tvInt: TextView = root.findViewById(R.id.tvHeroDetailInt)
    private val tvAc: TextView = root.findViewById(R.id.tvHeroDetailAc)

    private val slotHelmet: TextView = root.findViewById(R.id.equipSlotHelmet)
    private val slotArmor: TextView = root.findViewById(R.id.equipSlotArmor)
    private val slotWeapon1: TextView = root.findViewById(R.id.equipSlotWeapon1)
    private val slotWeapon2: TextView = root.findViewById(R.id.equipSlotWeapon2)
    private val slotBoots: TextView = root.findViewById(R.id.equipSlotBoots)

    private val btnSkills: MaterialButton = root.findViewById(R.id.btnHeroDetailSkills)
    private val btnClose: MaterialButton = root.findViewById(R.id.btnHeroDetailClose)

    private val ctx: Context get() = root.context
    private val skillsDialog: HeroSkillsDialog = HeroSkillsDialog(ctx)

    /**
     * Tracks the hero the panel is currently showing so the
     * "Skills & Spells" button knows whose skills to display when
     * tapped.
     */
    private var currentHero: Hero? = null

    init {
        root.setOnClickListener { hide() }
        panel.setOnClickListener { /* consume so taps don't dismiss */ }
        btnClose.setOnClickListener { hide() }
        btnSkills.setOnClickListener {
            currentHero?.let { skillsDialog.show(it) }
        }
    }

    fun show(hero: Hero) {
        currentHero = hero
        tvHeader.text = ctx.getString(
            R.string.hero_detail_header_format,
            hero.name,
            classDisplayName(hero.heroClass),
        )
        tvLevel.text = ctx.getString(R.string.hero_detail_level_format, hero.level)
        tvHp.text = ctx.getString(R.string.hero_detail_hp_format, hero.hp, hero.maxHp)
        tvMp.text = ctx.getString(R.string.hero_detail_mp_format, hero.mp, hero.maxMp)
        tvXp.text = ctx.getString(
            R.string.hero_detail_xp_format,
            hero.xp,
            hero.xpForNextLevel,
        )
        tvStr.text = ctx.getString(R.string.hero_detail_str_format, hero.strength)
        tvDex.text = ctx.getString(R.string.hero_detail_dex_format, hero.dexterity)
        tvInt.text = ctx.getString(R.string.hero_detail_int_format, hero.intelligence)
        tvAc.text = ctx.getString(R.string.hero_detail_ac_format, hero.armorClass)

        bindSlot(slotHelmet, R.string.hero_detail_slot_helmet, hero.helmet)
        bindSlot(slotArmor, R.string.hero_detail_slot_armor, hero.armor?.displayName)
        bindSlot(slotWeapon1, R.string.hero_detail_slot_weapon1, hero.weapon1?.displayName)
        bindSlot(slotWeapon2, R.string.hero_detail_slot_weapon2, hero.weapon2?.displayName)
        bindSlot(slotBoots, R.string.hero_detail_slot_boots, hero.boots)

        scroll.scrollTo(0, 0)
        root.visibility = View.VISIBLE
    }

    fun hide() {
        if (root.visibility == View.GONE) return
        root.visibility = View.GONE
        currentHero = null
        onDismiss?.invoke()
    }

    val isVisible: Boolean get() = root.visibility == View.VISIBLE

    private fun bindSlot(view: TextView, labelRes: Int, item: String?) {
        val label = ctx.getString(labelRes)
        val secondLine = item ?: ctx.getString(R.string.hero_detail_slot_empty)
        view.text = "$label\n$secondLine"
    }

    private fun classDisplayName(cls: HeroClass): String =
        cls.name.lowercase().replaceFirstChar { it.uppercaseChar() }
}
