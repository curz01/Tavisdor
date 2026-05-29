package com.tavisdor.app.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.GridLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.tavisdor.app.R
import com.tavisdor.app.dungeon.TreasureChest
import com.tavisdor.app.items.FloorKey
import com.tavisdor.app.items.Ingredient
import com.tavisdor.app.items.Inventory
import com.tavisdor.app.items.InventoryCapacity
import com.tavisdor.app.items.LootDrop
import com.tavisdor.app.items.Potion
import com.tavisdor.app.items.Weapon
import com.tavisdor.app.party.HeroEquipment
import com.tavisdor.app.party.Party

/**
 * Controller for the modal items / inventory panel
 * (`R.layout.activity_main` `itemsOverlay`).
 *
 * Battle loot sits in [Inventory.pendingPickup] until the player picks
 * it up manually, taps Pick Up All, or closes the panel with
 * auto-pickup enabled (items that fit are deposited; the rest are
 * discarded).
 */
class ItemsScreen(
    private val root: ViewGroup,
    var onDismiss: (() -> Unit)? = null,
) {
    var onPartyEquipmentChanged: (() -> Unit)? = null
    var onUsePotionSelf: (() -> Int?)? = null
    /** Refreshes dungeon sprites when chest loot is taken (e.g. empty -> treasure3). */
    var onChestLootChanged: (() -> Unit)? = null

    private data class PendingWeaponEquip(
        val heroSlot: Int,
        val weaponSlot: HeroEquipment.WeaponSlot,
    )

    private val ctx: Context get() = root.context

    private val panelHost: View = root.findViewById(R.id.itemsPanelHost)
    private val panel: View = root.findViewById(R.id.itemsPanel)

    private val sideTabPickup: View = root.findViewById(R.id.itemsSideTabPickup)
    private val sideTabEquipment: View = root.findViewById(R.id.itemsSideTabEquipment)
    private val sideTabIngredients: View = root.findViewById(R.id.itemsSideTabIngredients)

    private val tabPickup: View = root.findViewById(R.id.itemsTabPickup)
    private val tabEquipment: View = root.findViewById(R.id.itemsTabEquipment)
    private val tabIngredients: View = root.findViewById(R.id.itemsTabIngredients)

    private val tvGold: TextView = root.findViewById(R.id.tvItemsGold)
    private val tvEquipHint: TextView = root.findViewById(R.id.tvItemsEquipHint)
    private val tvTabPickup: TextView = root.findViewById(R.id.tvItemsTabPickup)
    private val tvTabEquip: TextView = root.findViewById(R.id.tvItemsTabEquip)
    private val tvTabMat: TextView = root.findViewById(R.id.tvItemsTabMat)
    private val tvPickupHint: TextView = root.findViewById(R.id.tvItemsPickupHint)
    private val btnClose: MaterialButton = root.findViewById(R.id.btnItemsClose)
    private val btnPickUpAll: MaterialButton = root.findViewById(R.id.btnItemsPickUpAll)
    private val cbAutoPickup: CheckBox = root.findViewById(R.id.cbItemsAutoPickup)

    private val pickupGrid = InventoryTabGrid(
        root.findViewById(R.id.itemsPickupGrid),
        R.drawable.bg_inventory_slot_pickup,
        R.drawable.bg_inventory_slot_pickup_empty,
    )
    private val equipmentGrid = InventoryTabGrid(
        root.findViewById(R.id.itemsEquipmentGrid),
        R.drawable.bg_inventory_slot_equipment,
        R.drawable.bg_inventory_slot_equipment_empty,
    )
    private val materialsGrid = InventoryTabGrid(
        root.findViewById(R.id.itemsMaterialsGrid),
        R.drawable.bg_inventory_slot_materials,
        R.drawable.bg_inventory_slot_materials_empty,
    )

    private enum class InventoryTab { PICKUP, EQUIPMENT, INGREDIENTS }

    private val sideTabs: List<View> by lazy {
        listOf(sideTabPickup, sideTabEquipment, sideTabIngredients)
    }

    private val heroSummaryPanel: View = root.findViewById(R.id.itemsHeroSummaryPanel)

    private val heroSummarySlots: List<InventoryHeroSummarySlot> by lazy {
        listOf(
            InventoryHeroSummarySlot(root.findViewById(R.id.heroSummarySlot0)),
            InventoryHeroSummarySlot(root.findViewById(R.id.heroSummarySlot1)),
            InventoryHeroSummarySlot(root.findViewById(R.id.heroSummarySlot2)),
            InventoryHeroSummarySlot(root.findViewById(R.id.heroSummarySlot3)),
        )
    }

    private val heroEquipPanel: InventoryHeroEquipPanel = InventoryHeroEquipPanel(
        root = root.findViewById(R.id.itemsHeroEquipPanel),
    ).also {
        it.onClose = { showHeroSummary() }
        it.onSlotClick = { slot -> onEquipSlotClicked(slot) }
    }

    private var selectedHeroSlot: Int? = null
    private var pendingWeaponEquip: PendingWeaponEquip? = null
    private var party: Party? = null
    private var chestLootSource: TreasureChest? = null

    /** Guards [performPickUpAll] against re-entry via [Inventory.onChanged]. */
    private var pickUpAllInProgress = false

    private val inventoryListener: () -> Unit = { refresh() }

    init {
        root.setOnClickListener { hide() }
        panelHost.setOnClickListener { /* consume */ }
        panel.setOnClickListener { /* consume */ }
        btnClose.setOnClickListener { hide() }
        btnPickUpAll.setOnClickListener { onPickUpAllTapped() }

        cbAutoPickup.isChecked = isAutoPickupEnabled()
        cbAutoPickup.setOnCheckedChangeListener { _, checked ->
            setAutoPickupEnabled(checked)
        }

        sideTabs.forEachIndexed { index, tab ->
            tab.setOnClickListener {
                if (index != InventoryTab.EQUIPMENT.ordinal) {
                    clearPendingWeaponEquip()
                }
                selectTab(index)
            }
        }

        heroSummarySlots.forEachIndexed { index, slot ->
            slot.root.setOnClickListener { openHeroEquipment(index) }
        }

        pickupGrid.setOnSlotClick { index -> onPickupSlotTapped(index) }
        equipmentGrid.setOnSlotClick { index -> onEquipmentSlotTapped(index) }
        materialsGrid.setOnSlotClick { index -> onMaterialsSlotTapped(index) }
    }

    fun bind(party: Party) {
        this.party = party
    }

    fun show() {
        val p = party ?: return
        p.inventory.onChanged = inventoryListener
        cbAutoPickup.isChecked = isAutoPickupEnabled()
        showHeroSummary()
        selectTab(InventoryTab.PICKUP.ordinal)
        refresh()
        root.visibility = View.VISIBLE
    }

    /** Shows pickup tab loot still inside [chest] (not party pending pickup). */
    fun showChestLoot(chest: TreasureChest) {
        chestLootSource = chest
        show()
    }

    private fun showHeroSummary() {
        selectedHeroSlot = null
        clearPendingWeaponEquip()
        heroSummaryPanel.visibility = View.VISIBLE
        heroEquipPanel.hide()
    }

    private fun clearPendingWeaponEquip() {
        pendingWeaponEquip = null
        tvEquipHint.visibility = View.GONE
    }

    private fun openHeroEquipment(slotIndex: Int) {
        val hero = party?.heroes?.getOrNull(slotIndex) ?: return
        selectedHeroSlot = slotIndex
        heroEquipPanel.bind(hero)
        heroSummaryPanel.visibility = View.GONE
        heroEquipPanel.show()
    }

    private fun selectTab(index: Int) {
        showTab(index)
        sideTabs.forEachIndexed { i, tab ->
            tab.isSelected = i == index
        }
        refresh()
    }

    private fun showTab(index: Int) {
        tabPickup.visibility = if (index == InventoryTab.PICKUP.ordinal) View.VISIBLE else View.GONE
        tabEquipment.visibility = if (index == InventoryTab.EQUIPMENT.ordinal) View.VISIBLE else View.GONE
        tabIngredients.visibility = if (index == InventoryTab.INGREDIENTS.ordinal) View.VISIBLE else View.GONE
    }

    fun hide() {
        if (root.visibility == View.GONE) return
        showHeroSummary()
        if (chestLootSource != null) {
            chestLootSource = null
            party?.inventory?.onChanged = null
            root.visibility = View.GONE
            onDismiss?.invoke()
            return
        }
        if (isAutoPickupEnabled()) {
            performPickUpAll()
        }
        val discarded = party?.inventory?.discardPendingPickup() ?: 0
        party?.inventory?.onChanged = null
        root.visibility = View.GONE
        if (discarded > 0) {
            AppToast.show(ctx, ctx.getString(R.string.items_panel_toast_discarded, discarded))
        }
        onDismiss?.invoke()
    }

    val isVisible: Boolean get() = root.visibility == View.VISIBLE

    private fun onPickUpAllTapped() {
        if (chestLootSource != null) {
            val collected = performChestPickUpAll()
            if (collected.isEmpty()) {
                val chest = chestLootSource
                if (chest != null && !chest.loot.isEmpty()) {
                    AppToast.show(
                        ctx,
                        ctx.getString(
                            R.string.items_panel_inventory_full,
                            InventoryCapacity.SLOTS_PER_TAB,
                        ),
                    )
                }
            }
            return
        }
        val collected = performPickUpAll()
        if (collected.isEmpty()) {
            val inv = party?.inventory
            if (inv != null && inv.hasPendingPickup) {
                AppToast.show(
                    ctx,
                    ctx.getString(
                        R.string.items_panel_inventory_full,
                        InventoryCapacity.SLOTS_PER_TAB,
                    ),
                )
            }
        }
    }

    private fun performPickUpAll(): List<LootDrop> {
        if (pickUpAllInProgress) return emptyList()
        val p = party ?: return emptyList()
        pickUpAllInProgress = true
        val remainingBefore = p.inventory.pendingPickup.size
        val collected = try {
            p.inventory.pickUpAll(p)
        } finally {
            pickUpAllInProgress = false
        }
        if (collected.isEmpty()) return emptyList()
        val remainingAfter = p.inventory.pendingPickup.size
        val label = if (collected.size == 1) {
            displayName(collected.first())
        } else {
            "${collected.size} items"
        }
        if (remainingAfter > 0 && remainingBefore > collected.size) {
            AppToast.show(ctx, ctx.getString(R.string.items_panel_pickup_partial, label))
        } else {
            AppToast.show(ctx, ctx.getString(R.string.items_panel_toast_picked_up, label))
        }
        return collected
    }

    private fun refresh() {
        val p = party ?: return
        val inv = p.inventory
        tvGold.text = ctx.getString(R.string.items_panel_gold_format, p.gold)

        bindTabLabels(inv)

        renderPickup(inv)
        renderEquipment(inv.weapons)
        renderMaterials(inv.ingredients, inv.potions, inv.floorKeys)

        val slot = selectedHeroSlot
        if (slot != null) {
            p.heroes.getOrNull(slot)?.let { heroEquipPanel.bind(it) }
        } else {
            renderHeroSummary(p)
        }
    }

    private fun bindTabLabels(inv: Inventory) {
        tvTabPickup.text = ctx.getString(R.string.items_panel_tab_pickup)
        val max = InventoryCapacity.SLOTS_PER_TAB
        tvTabEquip.text = ctx.getString(
            R.string.items_panel_tab_equip_format,
            inv.usedEquipmentSlots,
            max,
        )
        tvTabMat.text = ctx.getString(
            R.string.items_panel_tab_mat_format,
            inv.usedMaterialsSlots,
            max,
        )
    }

    private fun renderHeroSummary(party: Party) {
        party.heroes.forEachIndexed { index, hero ->
            heroSummarySlots[index].bind(hero)
        }
    }

    private fun renderEquipment(weapons: List<Weapon>) {
        tvEquipHint.visibility = if (pendingWeaponEquip != null) View.VISIBLE else View.GONE
        val picking = pendingWeaponEquip != null
        equipmentGrid.setOnSlotClick(if (picking) ::onEquipmentSlotTapped else null)
        equipmentGrid.bind(weapons.map { it.displayName })
    }

    private fun renderMaterials(
        ingredients: List<Ingredient>,
        potions: List<Potion>,
        floorKeys: List<FloorKey>,
    ) {
        val labels = buildList {
            ingredients.forEach { add(it.displayName) }
            repeat(potions.size) { add(Potion.DISPLAY_NAME) }
            floorKeys.forEach { add(it.displayName()) }
        }
        materialsGrid.bind(labels)
    }

    private fun renderPickup(inv: Inventory) {
        val chest = chestLootSource
        if (chest != null) {
            val labels = chestPickupLabels(chest)
            val hasLoot = labels.isNotEmpty()
            tvPickupHint.visibility = if (hasLoot) View.VISIBLE else View.GONE
            btnPickUpAll.isEnabled = hasLoot
            pickupGrid.setOnSlotClick(if (hasLoot) ::onPickupSlotTapped else null)
            pickupGrid.bind(labels)
            return
        }
        val pending = inv.pendingPickup
        val hasLoot = pending.isNotEmpty()
        tvPickupHint.visibility = if (hasLoot) View.VISIBLE else View.GONE
        btnPickUpAll.isEnabled = hasLoot
        pickupGrid.setOnSlotClick(if (hasLoot) ::onPickupSlotTapped else null)
        pickupGrid.bind(pending.map { displayName(it) })
    }

    private fun chestPickupLabels(chest: TreasureChest): List<String> {
        val labels = ArrayList<String>()
        if (chest.loot.gold > 0) {
            labels += ctx.getString(R.string.items_panel_chest_gold_format, chest.loot.gold)
        }
        chest.loot.items.forEach { labels += displayName(it) }
        return labels
    }

    private fun onEquipSlotClicked(slot: InventoryHeroEquipPanel.EquipSlot) {
        val heroSlot = selectedHeroSlot ?: return
        val p = party ?: return
        val hero = p.heroes.getOrNull(heroSlot) ?: return
        when (slot) {
            InventoryHeroEquipPanel.EquipSlot.PRIMARY_WEAPON ->
                onWeaponSlotClicked(heroSlot, HeroEquipment.WeaponSlot.PRIMARY, hero.weapon1)
            InventoryHeroEquipPanel.EquipSlot.OFF_HAND_WEAPON ->
                onWeaponSlotClicked(heroSlot, HeroEquipment.WeaponSlot.OFF_HAND, hero.weapon2)
            InventoryHeroEquipPanel.EquipSlot.HELMET,
            InventoryHeroEquipPanel.EquipSlot.ARMOR,
            InventoryHeroEquipPanel.EquipSlot.BOOTS,
            -> AppToast.show(ctx, R.string.inventory_toast_armor_not_in_gear)
        }
    }

    private fun onWeaponSlotClicked(
        heroSlot: Int,
        weaponSlot: HeroEquipment.WeaponSlot,
        equipped: Weapon?,
    ) {
        if (equipped != null) {
            unequipWeapon(heroSlot, weaponSlot, equipped.displayName)
            return
        }
        pendingWeaponEquip = PendingWeaponEquip(heroSlot, weaponSlot)
        selectTab(InventoryTab.EQUIPMENT.ordinal)
    }

    private fun onEquipmentSlotTapped(index: Int) {
        if (pendingWeaponEquip == null) return
        val p = party ?: return
        val weapon = p.inventory.weapons.getOrNull(index) ?: return
        equipWeapon(pendingWeaponEquip!!.heroSlot, pendingWeaponEquip!!.weaponSlot, weapon)
    }

    private fun equipWeapon(heroSlot: Int, weaponSlot: HeroEquipment.WeaponSlot, weapon: Weapon) {
        val p = party ?: return
        when (HeroEquipment.equipWeapon(p, heroSlot, weaponSlot, weapon)) {
            HeroEquipment.EquipResult.SUCCESS -> {
                clearPendingWeaponEquip()
                AppToast.show(ctx, ctx.getString(R.string.inventory_toast_equipped, weapon.displayName))
                onPartyEquipmentChanged?.invoke()
            }
            HeroEquipment.EquipResult.NOT_USABLE_BY_HERO -> {
                val hero = p.heroes[heroSlot]
                AppToast.show(ctx, ctx.getString(R.string.inventory_toast_cannot_equip, hero.name))
            }
            HeroEquipment.EquipResult.NOT_IN_INVENTORY -> refresh()
        }
        refresh()
    }

    private fun unequipWeapon(heroSlot: Int, weaponSlot: HeroEquipment.WeaponSlot, displayName: String) {
        val p = party ?: return
        if (!HeroEquipment.unequipWeapon(p, heroSlot, weaponSlot)) {
            AppToast.show(
                ctx,
                ctx.getString(
                    R.string.items_panel_inventory_full,
                    InventoryCapacity.SLOTS_PER_TAB,
                ),
            )
            return
        }
        clearPendingWeaponEquip()
        AppToast.show(ctx, ctx.getString(R.string.inventory_toast_unequipped, displayName))
        onPartyEquipmentChanged?.invoke()
        refresh()
    }

    private fun onMaterialsSlotTapped(index: Int) {
        val inv = party?.inventory ?: return
        val ingEnd = inv.ingredients.size
        val potEnd = ingEnd + inv.potions.size
        if (index in ingEnd until potEnd) {
            onPotionRowTapped()
        }
    }

    private fun onPotionRowTapped() {
        if (party?.inventory?.potions.isNullOrEmpty()) return
        val restored = onUsePotionSelf?.invoke()
        when {
            restored != null && restored > 0 -> {
                AppToast.show(ctx, ctx.getString(R.string.potion_used_toast, restored))
                onPartyEquipmentChanged?.invoke()
            }
            restored != null -> AppToast.show(ctx, R.string.potion_no_effect)
            else -> AppToast.show(ctx, R.string.potion_use_failed)
        }
        refresh()
    }

    private fun onPickupSlotTapped(index: Int) {
        if (chestLootSource != null) {
            onChestPickupSlotTapped(index)
            return
        }
        val p = party ?: return
        val picked = p.inventory.pickUpAt(index, p)
        when {
            picked != null ->
                AppToast.show(ctx, ctx.getString(R.string.items_panel_toast_picked_up, displayName(picked)))
            p.inventory.pendingPickup.getOrNull(index) != null ->
                AppToast.show(
                    ctx,
                    ctx.getString(
                        R.string.items_panel_inventory_full,
                        InventoryCapacity.SLOTS_PER_TAB,
                    ),
                )
        }
    }

    private fun onChestPickupSlotTapped(index: Int) {
        val chest = chestLootSource ?: return
        val p = party ?: return
        val label = pickUpChestRow(chest, p, index)
        when {
            label != null ->
                AppToast.show(ctx, ctx.getString(R.string.items_panel_toast_picked_up, label))
            chestPickupLabels(chest).getOrNull(index) != null ->
                AppToast.show(
                    ctx,
                    ctx.getString(
                        R.string.items_panel_inventory_full,
                        InventoryCapacity.SLOTS_PER_TAB,
                    ),
                )
        }
        if (chest.loot.isEmpty()) {
            onChestLootChanged?.invoke()
        }
        refresh()
    }

    private fun performChestPickUpAll(): List<String> {
        val chest = chestLootSource ?: return emptyList()
        val p = party ?: return emptyList()
        val collected = ArrayList<String>()
        while (chest.loot.gold > 0) {
            val taken = chest.loot.removeGold(chest.loot.gold)
            p.addGold(taken)
            collected += ctx.getString(R.string.items_panel_chest_gold_format, taken)
        }
        val remaining = chest.loot.items.toList()
        for (drop in remaining) {
            if (!p.inventory.tryDepositLoot(drop, p)) break
            chest.loot.items.remove(drop)
            collected += displayName(drop)
        }
        if (chest.loot.isEmpty()) {
            onChestLootChanged?.invoke()
        }
        refresh()
        return collected
    }

    private fun pickUpChestRow(chest: TreasureChest, party: Party, index: Int): String? {
        var cursor = 0
        if (chest.loot.gold > 0) {
            if (index == cursor) {
                val taken = chest.loot.removeGold(chest.loot.gold)
                party.addGold(taken)
                return ctx.getString(R.string.items_panel_chest_gold_format, taken)
            }
            cursor++
        }
        val itemIndex = index - cursor
        val drop = chest.loot.items.getOrNull(itemIndex) ?: return null
        if (!party.inventory.tryDepositLoot(drop, party)) return null
        chest.loot.items.removeAt(itemIndex)
        return displayName(drop)
    }

    private fun displayName(drop: LootDrop): String = when (drop) {
        is LootDrop.IngredientDrop -> drop.ingredient.displayName
        is LootDrop.MeleeWeaponDrop -> drop.tier.displayMeleeName(drop.weapon)
        is LootDrop.FloorKeyDrop -> drop.key.displayName()
        is LootDrop.ArmorDrop -> drop.armorName
    }

    private fun isAutoPickupEnabled(): Boolean =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_PICKUP, true)

    private fun setAutoPickupEnabled(enabled: Boolean) {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_PICKUP, enabled)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "tavisdor_items_panel"
        private const val KEY_AUTO_PICKUP = "auto_pickup"
    }
}
