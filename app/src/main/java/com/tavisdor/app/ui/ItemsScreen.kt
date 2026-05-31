package com.tavisdor.app.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.content.ContextCompat
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
import com.tavisdor.app.items.InventoryGridSlot
import com.tavisdor.app.items.InventoryStacks
import com.tavisdor.app.items.LootDrop
import com.tavisdor.app.items.Potion
import com.tavisdor.app.items.ArmorItem
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
    /** [stackIndex] is the Materials-tab grid index of the potion stack tapped. */
    var onUsePotionSelf: ((stackIndex: Int) -> Int?)? = null
    /** Refreshes dungeon sprites when chest loot is taken (e.g. empty -> treasure3). */
    var onChestLootChanged: (() -> Unit)? = null

    /**
     * Fired after each successful pending-pickup deposit (single tap
     * or Pick Up All step). Return true to stop further bulk pickup
     * and let the host dismiss the panel without discarding loot.
     */
    var onAfterLootPickup: (() -> Boolean)? = null

    private data class PendingWeaponEquip(
        val heroSlot: Int,
        val weaponSlot: HeroEquipment.WeaponSlot,
    )

    private data class PendingArmorEquip(val heroSlot: Int)

    private val ctx: Context get() = root.context

    private val panelHost: View = root.findViewById(R.id.itemsPanelHost)
    private val panel: ViewGroup = root.findViewById(R.id.itemsPanel)

    private val sideTabPickup: View = root.findViewById(R.id.itemsSideTabPickup)
    private val sideTabEquipment: View = root.findViewById(R.id.itemsSideTabEquipment)
    private val sideTabIngredients: View = root.findViewById(R.id.itemsSideTabIngredients)

    private val tabBody: View = root.findViewById(R.id.itemsTabBody)
    private val tabPickup: View = root.findViewById(R.id.itemsTabPickup)
    private val tabEquipment: View = root.findViewById(R.id.itemsTabEquipment)
    private val tabIngredients: View = root.findViewById(R.id.itemsTabIngredients)

    private val tvGold: TextView = root.findViewById(R.id.tvItemsGold)
    private val tvEquipHint: TextView = root.findViewById(R.id.tvItemsEquipHint)
    private val tvTabPickup: TextView = root.findViewById(R.id.tvItemsTabPickup)
    private val tvTabEquip: TextView = root.findViewById(R.id.tvItemsTabEquip)
    private val tvTabMat: TextView = root.findViewById(R.id.tvItemsTabMat)
    private val btnClose: MaterialButton = root.findViewById(R.id.btnItemsClose)
    private val btnPickUpAll: MaterialButton = root.findViewById(R.id.btnItemsPickUpAll)
    private val cbAutoPickup: CheckBox = root.findViewById(R.id.cbItemsAutoPickup)

    private val pickupGrid = InventoryTabGrid(root.findViewById(R.id.itemsPickupGrid))
    private val equipmentGrid = InventoryTabGrid(root.findViewById(R.id.itemsEquipmentGrid))
    private val materialsGrid = InventoryTabGrid(root.findViewById(R.id.itemsMaterialsGrid))

    private val tabGrids: List<InventoryTabGrid> = listOf(pickupGrid, equipmentGrid, materialsGrid)

    private enum class InventoryTab { PICKUP, EQUIPMENT, INGREDIENTS }

    private val sideTabs: List<View> = listOf(sideTabPickup, sideTabEquipment, sideTabIngredients)

    private val tabInactiveLiftPx: Float =
        ctx.resources.getDimension(R.dimen.inventory_tab_inactive_lift)
    private val tabActiveOverlapPx: Float =
        ctx.resources.getDimension(R.dimen.inventory_tab_active_overlap)
    private val tabHeightActivePx: Int =
        ctx.resources.getDimensionPixelSize(R.dimen.inventory_tab_height_active)
    private val tabHeightInactivePx: Int =
        ctx.resources.getDimensionPixelSize(R.dimen.inventory_tab_height_inactive)

    private val heroLowerHost: View = root.findViewById(R.id.itemsHeroLowerHost)
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
        it.onPrevHero = { cycleHeroEquipment(-1) }
        it.onNextHero = { cycleHeroEquipment(1) }
        it.onSlotClick = { slot -> onEquipSlotClicked(slot) }
    }

    private var selectedHeroSlot: Int? = null
    /** Cached height of the 2x2 hero summary so the equipment overlay matches. */
    private var pinnedHeroLowerHeightPx: Int = 0
    /** Opens equipment after the summary panel height has been measured. */
    private var pendingEquipSlotAfterPin: Int? = null
    private var pendingWeaponEquip: PendingWeaponEquip? = null
    private var pendingArmorEquip: PendingArmorEquip? = null
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
                    clearPendingEquip()
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

        panel.clipChildren = false
        panel.clipToPadding = false
        applyTabChrome(InventoryPanelTab.PICKUP)
    }

    fun bind(party: Party) {
        this.party = party
    }

    fun show() {
        val p = party ?: return
        p.inventory.onChanged = inventoryListener
        cbAutoPickup.isChecked = isAutoPickupEnabled()
        pinnedHeroLowerHeightPx = 0
        pendingEquipSlotAfterPin = null
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
        clearPendingEquip()
        heroSummaryPanel.visibility = View.VISIBLE
        heroEquipPanel.hide()
        schedulePinHeroLowerHostHeight()
    }

    /**
     * Locks [itemsHeroLowerHost] to the measured height of the party summary
     * grid so opening per-hero equipment does not resize the inventory panel.
     */
    private fun schedulePinHeroLowerHostHeight() {
        if (pinnedHeroLowerHeightPx > 0) {
            applyHeroLowerHostHeight(pinnedHeroLowerHeightPx)
            return
        }
        if (heroSummaryPanel.visibility != View.VISIBLE) return
        heroSummaryPanel.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    heroSummaryPanel.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    if (heroSummaryPanel.visibility != View.VISIBLE) return
                    val measured = heroSummaryPanel.height
                    if (measured <= 0) return
                    pinnedHeroLowerHeightPx = measured
                    applyHeroLowerHostHeight(measured)
                    pendingEquipSlotAfterPin?.let { slot ->
                        pendingEquipSlotAfterPin = null
                        finishOpenHeroEquipment(slot)
                    }
                }
            },
        )
    }

    private fun applyHeroLowerHostHeight(heightPx: Int) {
        val lp = heroLowerHost.layoutParams ?: return
        if (lp.height == heightPx) return
        lp.height = heightPx
        heroLowerHost.layoutParams = lp
    }

    private fun clearPendingWeaponEquip() {
        pendingWeaponEquip = null
        if (pendingArmorEquip == null) {
            tvEquipHint.visibility = View.GONE
        }
    }

    private fun clearPendingArmorEquip() {
        pendingArmorEquip = null
        if (pendingWeaponEquip == null) {
            tvEquipHint.visibility = View.GONE
        }
    }

    private fun clearPendingEquip() {
        pendingWeaponEquip = null
        pendingArmorEquip = null
        tvEquipHint.visibility = View.GONE
    }

    private fun openHeroEquipment(slotIndex: Int) {
        if (party?.heroes?.getOrNull(slotIndex) == null) return
        if (pinnedHeroLowerHeightPx <= 0) {
            pendingEquipSlotAfterPin = slotIndex
            heroSummaryPanel.visibility = View.VISIBLE
            heroEquipPanel.hide()
            schedulePinHeroLowerHostHeight()
            return
        }
        finishOpenHeroEquipment(slotIndex)
    }

    private fun finishOpenHeroEquipment(slotIndex: Int) {
        val hero = party?.heroes?.getOrNull(slotIndex) ?: return
        applyHeroLowerHostHeight(pinnedHeroLowerHeightPx)
        selectedHeroSlot = slotIndex
        heroEquipPanel.bind(hero)
        heroSummaryPanel.visibility = View.GONE
        heroEquipPanel.show()
    }

    private fun cycleHeroEquipment(delta: Int) {
        val heroes = party?.heroes ?: return
        val current = selectedHeroSlot ?: return
        if (heroes.isEmpty()) return
        val next = (current + delta).mod(heroes.size)
        openHeroEquipment(next)
    }

    private fun selectTab(index: Int) {
        showTab(index)
        applyTabChrome(InventoryPanelTab.fromOrdinal(index))
        refresh()
    }

    /**
     * Active tab lowers onto the panel frame; inactive tabs lift. Panel + slot
     * borders use the active tab accent (see mockup: Mat = tan, Loot = green, etc.).
     */
    private fun applyTabChrome(tab: InventoryPanelTab) {
        val accent = ContextCompat.getColor(ctx, tab.accentColorRes)
        tabBody.background = InventorySlotDrawables.tabBodyFrame(ctx, accent)

        sideTabs.forEachIndexed { index, tabView ->
            val selected = index == tab.ordinal
            tabView.isSelected = selected
            tabView.translationY = if (selected) {
                tabActiveOverlapPx
            } else {
                -tabInactiveLiftPx
            }
            tabView.elevation = if (selected) 6f else 0f
            tabView.layoutParams = tabView.layoutParams.apply {
                height = if (selected) tabHeightActivePx else tabHeightInactivePx
            }
        }

        tabGrids.forEach { it.applyTheme(tab) }
    }

    private fun showTab(index: Int) {
        tabPickup.visibility = if (index == InventoryTab.PICKUP.ordinal) View.VISIBLE else View.GONE
        tabEquipment.visibility = if (index == InventoryTab.EQUIPMENT.ordinal) View.VISIBLE else View.GONE
        tabIngredients.visibility = if (index == InventoryTab.INGREDIENTS.ordinal) View.VISIBLE else View.GONE
    }

    fun hide() {
        dismissPendingLoot(discardUnclaimed = true)
    }

    /**
     * Closes the panel without discarding [Inventory.pendingPickup].
     * Used when hide is broken mid-loot and combat resumes.
     */
    fun dismissKeepPendingLoot() {
        dismissPendingLoot(discardUnclaimed = false)
    }

    private fun dismissPendingLoot(discardUnclaimed: Boolean) {
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
        val discarded = if (discardUnclaimed) {
            party?.inventory?.discardPendingPickup() ?: 0
        } else {
            0
        }
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
        val collected = mutableListOf<LootDrop>()
        try {
            while (p.inventory.hasPendingPickup) {
                val picked = p.inventory.pickUpAt(0, p) ?: break
                collected += picked
                if (onAfterLootPickup?.invoke() == true) break
            }
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
        if (remainingAfter > 0) {
            AppToast.show(ctx, ctx.getString(R.string.items_panel_pickup_partial, label))
        } else {
            AppToast.show(ctx, ctx.getString(R.string.items_panel_toast_picked_up, label))
        }
        return collected
    }

    private fun notifyAfterLootPickup(): Boolean =
        onAfterLootPickup?.invoke() == true

    private fun refresh() {
        val p = party ?: return
        val inv = p.inventory
        tvGold.text = ctx.getString(R.string.items_panel_gold_format, p.gold)

        bindTabLabels(inv)

        renderPickup(inv)
        renderEquipment()
        renderMaterials()

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

    private fun renderEquipment() {
        val pickingWeapon = pendingWeaponEquip != null
        val pickingArmor = pendingArmorEquip != null
        val picking = pickingWeapon || pickingArmor
        tvEquipHint.visibility = if (picking) View.VISIBLE else View.GONE
        tvEquipHint.text = when {
            pickingWeapon -> ctx.getString(R.string.inventory_equip_pick_weapon)
            pickingArmor -> ctx.getString(R.string.inventory_equip_pick_armor)
            else -> ctx.getString(R.string.inventory_equip_pick_weapon)
        }
        equipmentGrid.setOnSlotClick(if (picking) ::onEquipmentSlotTapped else null)
        equipmentGrid.bind(party?.inventory?.equipmentGridSlots().orEmpty())
    }

    private fun renderMaterials() {
        materialsGrid.bind(party?.inventory?.materialGridSlots().orEmpty())
    }

    private fun renderPickup(inv: Inventory) {
        val chest = chestLootSource
        if (chest != null) {
            val slots = chestPickupSlots(chest)
            val hasLoot = slots.isNotEmpty()
            btnPickUpAll.isEnabled = hasLoot
            pickupGrid.setOnSlotClick(if (hasLoot) ::onPickupSlotTapped else null)
            pickupGrid.bind(slots)
            return
        }
        val pending = inv.pendingPickup
        val hasLoot = pending.isNotEmpty()
        btnPickUpAll.isEnabled = hasLoot
        pickupGrid.setOnSlotClick(if (hasLoot) ::onPickupSlotTapped else null)
        pickupGrid.bind(inv.pickupGridSlots(::displayName))
    }

    private fun chestPickupSlots(chest: TreasureChest): List<InventoryGridSlot> {
        val slots = ArrayList<InventoryGridSlot>()
        if (chest.loot.gold > 0) {
            slots += InventoryGridSlot(
                ctx.getString(R.string.items_panel_chest_gold_format, chest.loot.gold),
            )
        }
        slots += InventoryStacks.lootDropSlots(chest.loot.items, ::displayName)
        return slots
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
            InventoryHeroEquipPanel.EquipSlot.ARMOR ->
                onArmorSlotClicked(heroSlot, hero.armor)
            InventoryHeroEquipPanel.EquipSlot.HELMET,
            InventoryHeroEquipPanel.EquipSlot.BOOTS,
            -> AppToast.show(ctx, R.string.inventory_toast_armor_slot_not_stash)
        }
    }

    private fun onArmorSlotClicked(heroSlot: Int, equipped: ArmorItem?) {
        if (equipped != null) {
            unequipArmor(heroSlot, equipped.displayName)
            return
        }
        clearPendingWeaponEquip()
        pendingArmorEquip = PendingArmorEquip(heroSlot)
        selectTab(InventoryTab.EQUIPMENT.ordinal)
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
        clearPendingArmorEquip()
        pendingWeaponEquip = PendingWeaponEquip(heroSlot, weaponSlot)
        selectTab(InventoryTab.EQUIPMENT.ordinal)
    }

    private fun onEquipmentSlotTapped(index: Int) {
        val p = party ?: return
        pendingWeaponEquip?.let { pending ->
            val weapon = p.inventory.weaponAtStackIndex(index) ?: return
            equipWeapon(pending.heroSlot, pending.weaponSlot, weapon)
            return
        }
        pendingArmorEquip?.let { pending ->
            val armor = p.inventory.armorAtStackIndex(index) ?: return
            equipArmor(pending.heroSlot, armor)
        }
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

    private fun equipArmor(heroSlot: Int, armor: ArmorItem) {
        val p = party ?: return
        when (HeroEquipment.equipArmor(p, heroSlot, armor)) {
            HeroEquipment.EquipResult.SUCCESS -> {
                clearPendingArmorEquip()
                AppToast.show(ctx, ctx.getString(R.string.inventory_toast_equipped, armor.displayName))
                onPartyEquipmentChanged?.invoke()
            }
            HeroEquipment.EquipResult.NOT_IN_INVENTORY -> refresh()
            HeroEquipment.EquipResult.NOT_USABLE_BY_HERO -> Unit
        }
        refresh()
    }

    private fun unequipArmor(heroSlot: Int, displayName: String) {
        val p = party ?: return
        if (!HeroEquipment.unequipArmor(p, heroSlot)) {
            AppToast.show(
                ctx,
                ctx.getString(
                    R.string.items_panel_inventory_full,
                    InventoryCapacity.SLOTS_PER_TAB,
                ),
            )
            return
        }
        clearPendingArmorEquip()
        AppToast.show(ctx, ctx.getString(R.string.inventory_toast_unequipped, displayName))
        onPartyEquipmentChanged?.invoke()
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
        if (index !in inv.potionStackIndexRange()) return
        onPotionRowTapped(index)
    }

    private fun onPotionRowTapped(stackIndex: Int) {
        if (party?.inventory?.potions.isNullOrEmpty()) return
        val restored = onUsePotionSelf?.invoke(stackIndex)
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
            picked != null -> {
                AppToast.show(ctx, ctx.getString(R.string.items_panel_toast_picked_up, displayName(picked)))
                notifyAfterLootPickup()
            }
            p.inventory.pendingPickupAtStack(index) != null ->
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
            chestPickupSlots(chest).getOrNull(index) != null ->
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

    private fun pickUpChestRow(chest: TreasureChest, party: Party, stackIndex: Int): String? {
        var cursor = 0
        if (chest.loot.gold > 0) {
            if (stackIndex == cursor) {
                val taken = chest.loot.removeGold(chest.loot.gold)
                party.addGold(taken)
                return ctx.getString(R.string.items_panel_chest_gold_format, taken)
            }
            cursor++
        }
        val itemBacking = InventoryStacks.lootDropBackingIndex(
            chest.loot.items,
            stackIndex - cursor,
        ) ?: return null
        val drop = chest.loot.items.getOrNull(itemBacking) ?: return null
        if (!party.inventory.tryDepositLoot(drop, party)) return null
        chest.loot.items.removeAt(itemBacking)
        return displayName(drop)
    }

    private fun displayName(drop: LootDrop): String = when (drop) {
        is LootDrop.IngredientDrop -> drop.ingredient.displayName
        is LootDrop.MeleeWeaponDrop -> drop.displayName()
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
