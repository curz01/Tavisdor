package com.tavisdor.app.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.tavisdor.app.R
import com.tavisdor.app.items.FloorKey
import com.tavisdor.app.items.Ingredient
import com.tavisdor.app.items.Inventory
import com.tavisdor.app.items.LootDrop
import com.tavisdor.app.items.Weapon
import com.tavisdor.app.party.HeroEquipment
import com.tavisdor.app.party.Party

/**
 * Controller for the modal items / inventory panel
 * (`R.layout.activity_main` `itemsOverlay`).
 *
 * Layout summary (see the XML):
 *   - Outer FrameLayout is the scrim; tapping it dismisses the
 *     overlay (with the same "drops in pickup section get
 *     discarded" semantics as the close button).
 *   - Inner ScrollView holds a vertical LinearLayout (the
 *     "panel") that consumes touches so taps inside don't
 *     bubble out to the scrim.
 *   - Top row: gold counter (left) + Close (right).
 *   - Top tab strip (horizontal, right-aligned): Pickup, Gear, Mats.
 *   - Pickup tab: tappable rows, Pick Up All, discard-on-close.
 *   - Below: 2x2 party summary, or per-hero equipment when a card is tapped.
 *
 * Lifecycle:
 *   - Construct once per Activity; reuses the same view tree.
 *   - [bind] points at the active party. Subsequent [show]
 *     calls just refresh the rendering; combat-end deposits
 *     trickle in via the inventory's [Inventory.onChanged]
 *     callback so the panel re-renders the moment a kill drops
 *     loot even if the panel was already on screen.
 *   - [hide] tears down the listener and discards any leftover
 *     pickup rows; [onDismiss] fires last so MainActivity can
 *     clear its own state.
 */
class ItemsScreen(
    private val root: ViewGroup,
    var onDismiss: (() -> Unit)? = null,
) {
    /** Fired after a hero equips / unequips a weapon so the HUD can refresh. */
    var onPartyEquipmentChanged: (() -> Unit)? = null

    private data class PendingWeaponEquip(
        val heroSlot: Int,
        val weaponSlot: HeroEquipment.WeaponSlot,
    )
    private val ctx: Context get() = root.context

    private val panelHost: View = root.findViewById(R.id.itemsPanelHost)
    private val scroll: ScrollView = root.findViewById(R.id.itemsPanelScroll)
    private val panel: View = root.findViewById(R.id.itemsPanel)

    private val sideTabPickup: View = root.findViewById(R.id.itemsSideTabPickup)
    private val sideTabEquipment: View = root.findViewById(R.id.itemsSideTabEquipment)
    private val sideTabIngredients: View = root.findViewById(R.id.itemsSideTabIngredients)

    private val tabPickup: View = root.findViewById(R.id.itemsTabPickup)
    private val tabEquipment: View = root.findViewById(R.id.itemsTabEquipment)
    private val tabIngredients: View = root.findViewById(R.id.itemsTabIngredients)

    private val tvGold: TextView = root.findViewById(R.id.tvItemsGold)
    private val tvEquipHint: TextView = root.findViewById(R.id.tvItemsEquipHint)
    private val equipmentList: LinearLayout = root.findViewById(R.id.itemsEquipmentList)
    private val ingredientList: LinearLayout = root.findViewById(R.id.itemsIngredientList)
    private val pickupList: LinearLayout = root.findViewById(R.id.itemsPickupList)
    private val tvPickupHint: TextView = root.findViewById(R.id.tvItemsPickupHint)
    private val btnClose: MaterialButton = root.findViewById(R.id.btnItemsClose)
    private val btnPickUpAll: MaterialButton = root.findViewById(R.id.btnItemsPickUpAll)

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

    /** When set, the lower host shows [heroEquipPanel] instead of the 2x2 summary. */
    private var selectedHeroSlot: Int? = null

    /** Waiting for the player to pick a weapon from the Gear tab. */
    private var pendingWeaponEquip: PendingWeaponEquip? = null

    /**
     * Party currently surfaced by the panel. Set via [bind]; the
     * panel can be opened only after this has been wired (the
     * Items button is gated by `game.party != null`).
     */
    private var party: Party? = null

    /**
     * Latched listener installed on [Inventory.onChanged] while
     * the panel is visible so a kill-time deposit re-renders the
     * UI without the activity having to know about it. Stored
     * here so [hide] can detach it without dangling references.
     */
    private val inventoryListener: () -> Unit = { refresh() }

    init {
        root.setOnClickListener { hide() }
        panelHost.setOnClickListener { /* consume so taps on panel/tabs don't dismiss */ }
        panel.setOnClickListener { /* consume so taps don't dismiss */ }
        btnClose.setOnClickListener { hide() }
        btnPickUpAll.setOnClickListener { onPickUpAllTapped() }

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
    }

    /** Wire the panel to [party]. Idempotent. */
    fun bind(party: Party) {
        this.party = party
    }

    /**
     * Opens the panel and renders the current inventory state.
     * Subscribes to [Inventory.onChanged] for live updates while
     * visible (combat is over by the time this fires, but chest
     * pickups will deposit through the same channel).
     */
    fun show() {
        val p = party ?: return
        p.inventory.onChanged = inventoryListener
        showHeroSummary()
        selectTab(InventoryTab.PICKUP.ordinal)
        refresh()
        scroll.scrollTo(0, 0)
        root.visibility = View.VISIBLE
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
    }

    private fun showTab(index: Int) {
        tabPickup.visibility = if (index == InventoryTab.PICKUP.ordinal) View.VISIBLE else View.GONE
        tabEquipment.visibility = if (index == InventoryTab.EQUIPMENT.ordinal) View.VISIBLE else View.GONE
        tabIngredients.visibility = if (index == InventoryTab.INGREDIENTS.ordinal) View.VISIBLE else View.GONE
    }

    /**
     * Closes the panel. Per the user's spec, anything still in
     * the pickup / discard section is dropped on the floor (i.e.
     * removed without depositing anywhere). A toast surfaces
     * the discard count so the player notices they lost
     * something rather than wondering where it went.
     */
    fun hide() {
        if (root.visibility == View.GONE) return
        showHeroSummary()
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
        val p = party ?: return
        val collected = p.inventory.pickUpAll()
        if (collected.isEmpty()) return
        // refresh() runs via inventoryListener. The toast collapses
        // a multi-pickup into a single line so the player isn't
        // showered with N popups; the singular branch keeps the
        // wording natural ("Picked up Iron Spear.") rather than
        // "Picked up 1 item.".
        val label = if (collected.size == 1) {
            displayName(collected.first())
        } else {
            "${collected.size} items"
        }
        AppToast.show(ctx, ctx.getString(R.string.items_panel_toast_picked_up, label))
    }

    /**
     * Repaints every section against the current inventory.
     * Called on show, on inventory-change notifications, and
     * after explicit pickups.
     */
    private fun refresh() {
        val p = party ?: return
        tvGold.text = ctx.getString(R.string.items_panel_gold_format, p.gold)

        renderEquipment(p.inventory.weapons)
        renderIngredients(p.inventory.ingredients, p.inventory.floorKeys)
        renderPickup(p.inventory.pendingPickup)
        val slot = selectedHeroSlot
        if (slot != null) {
            p.heroes.getOrNull(slot)?.let { heroEquipPanel.bind(it) }
        } else {
            renderHeroSummary(p)
        }
    }

    /** Slots 0–1 front row, 2–3 back row — mirrors [HeroPanelView]. */
    private fun renderHeroSummary(party: Party) {
        party.heroes.forEachIndexed { index, hero ->
            heroSummarySlots[index].bind(hero)
        }
    }

    private fun renderEquipment(weapons: List<Weapon>) {
        tvEquipHint.visibility = if (pendingWeaponEquip != null) View.VISIBLE else View.GONE

        equipmentList.removeAllViews()
        if (weapons.isEmpty()) {
            equipmentList.addView(emptyRow())
            return
        }
        val picking = pendingWeaponEquip != null
        for (group in groupByLabel(weapons) { it.displayName }) {
            val label = formatStackLabel(group.label, group.count)
            equipmentList.addView(
                if (picking) {
                    gearPickRow(label, group.label)
                } else {
                    staticRow(label)
                },
            )
        }
    }

    private fun gearPickRow(label: String, displayName: String): View {
        val tv = baseRow(label)
        tv.setBackgroundResource(android.R.drawable.list_selector_background)
        tv.isClickable = true
        tv.isFocusable = true
        tv.setOnClickListener { onGearWeaponRowTapped(displayName) }
        return tv
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
            -> {
                AppToast.show(ctx, R.string.inventory_toast_armor_not_in_gear)
            }
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
        refresh()
    }

    private fun onGearWeaponRowTapped(displayName: String) {
        val pending = pendingWeaponEquip ?: return
        val p = party ?: return
        val idx = p.inventory.indexOfWeaponByDisplayName(displayName)
        val weapon = p.inventory.weapons.getOrNull(idx) ?: return
        equipWeapon(pending.heroSlot, pending.weaponSlot, weapon)
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
        if (!HeroEquipment.unequipWeapon(p, heroSlot, weaponSlot)) return
        clearPendingWeaponEquip()
        AppToast.show(ctx, ctx.getString(R.string.inventory_toast_unequipped, displayName))
        onPartyEquipmentChanged?.invoke()
        refresh()
    }

    private fun renderIngredients(ingredients: List<Ingredient>, floorKeys: List<FloorKey>) {
        ingredientList.removeAllViews()
        if (ingredients.isEmpty() && floorKeys.isEmpty()) {
            ingredientList.addView(emptyRow())
            return
        }
        for (group in groupByLabel(ingredients) { it.displayName }) {
            ingredientList.addView(staticRow(formatStackLabel(group.label, group.count)))
        }
        for (group in groupByLabel(floorKeys) { it.displayName() }) {
            ingredientList.addView(staticRow(formatStackLabel(group.label, group.count)))
        }
    }

    private fun renderPickup(pending: List<LootDrop>) {
        pickupList.removeAllViews()
        if (pending.isEmpty()) {
            pickupList.addView(emptyRow())
            btnPickUpAll.isEnabled = false
            tvPickupHint.visibility = View.GONE
            return
        }
        tvPickupHint.visibility = View.VISIBLE
        btnPickUpAll.isEnabled = true
        // Pickup rows also stack by display name. Tapping a
        // stacked row picks up ONE item from the underlying
        // queue (looked up by label, not by row index) so the
        // count visibly ticks down on each tap and the player
        // can still grab a single beer out of three. Use Pick
        // Up All to drain the whole queue in one go.
        for (group in groupByLabel(pending) { displayName(it) }) {
            pickupList.addView(pickupRow(group.label, group.count))
        }
    }

    private fun pickupRow(label: String, count: Int): View {
        val tv = baseRow(formatStackLabel(label, count))
        tv.setBackgroundResource(android.R.drawable.list_selector_background)
        tv.isClickable = true
        tv.isFocusable = true
        tv.setOnClickListener { onPickupRowTapped(label) }
        return tv
    }

    /**
     * Looks up the first [LootDrop] in [Inventory.pendingPickup]
     * whose display name matches the tapped row's label and picks
     * it up. Operating by label (not index) keeps the UI
     * grouped-by-name without forcing the pickup queue itself to
     * be a stack-aware data structure - the queue stays a flat
     * list of individual drops, and the panel does the grouping
     * purely for display.
     */
    private fun onPickupRowTapped(label: String) {
        val p = party ?: return
        val idx = p.inventory.pendingPickup.indexOfFirst { displayName(it) == label }
        if (idx < 0) return
        val picked = p.inventory.pickUpAt(idx) ?: return
        AppToast.show(ctx, ctx.getString(R.string.items_panel_toast_picked_up, displayName(picked)))
    }

    /**
     * Renders a [LootDrop] as the player-visible label used in
     * the pickup section. Mirrors the same display name the
     * destination section will use after pickup so the player
     * can confirm they're getting what they tapped on.
     */
    private fun displayName(drop: LootDrop): String = when (drop) {
        is LootDrop.IngredientDrop -> drop.ingredient.displayName
        is LootDrop.MeleeWeaponDrop -> drop.tier.displayMeleeName(drop.weapon)
        is LootDrop.FloorKeyDrop -> drop.key.displayName()
    }

    /**
     * One stack of identically-labeled items in a section. Used
     * by [groupByLabel] so the renderers can stack duplicates
     * into "Beer x 3" rows without losing first-occurrence order
     * (the first one in the source list anchors the row's position).
     */
    private data class StackRow(val label: String, val count: Int)

    /**
     * Collapses [items] into [StackRow]s by their [label]. First
     * occurrence wins for ordering so the section list reads in
     * the same sequence the player acquired items - new pickups
     * land at the bottom and stack into the first row that already
     * shares their label.
     */
    private inline fun <T> groupByLabel(items: List<T>, label: (T) -> String): List<StackRow> {
        if (items.isEmpty()) return emptyList()
        val counts = LinkedHashMap<String, Int>(items.size)
        for (item in items) {
            val key = label(item)
            counts[key] = (counts[key] ?: 0) + 1
        }
        return counts.map { (k, n) -> StackRow(k, n) }
    }

    /**
     * Formats one stack as "Label" for n == 1 or "Label x N" for
     * n >= 2. The "x" uses the lowercase ASCII letter rather than
     * the multiplication sign so it stays readable in every
     * locale-aware font / accessibility-large rendering.
     */
    private fun formatStackLabel(label: String, count: Int): String =
        if (count <= 1) label else "$label x $count"

    /** "(empty)" placeholder used for sections with no rows. */
    private fun emptyRow(): TextView {
        val tv = baseRow(ctx.getString(R.string.items_panel_section_empty))
        tv.setTextColor(ctx.getColor(R.color.items_panel_row_empty))
        return tv
    }

    /** Read-only row used by sections 1 / 2. */
    private fun staticRow(label: String): TextView = baseRow(label)

    /**
     * Builds the common row TextView. Centralized so future
     * changes (icons, quantity badges, dividers) only touch
     * one place.
     */
    private fun baseRow(label: String): TextView {
        val tv = TextView(ctx)
        tv.text = label
        tv.setTextColor(ctx.getColor(R.color.items_panel_row_text))
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        tv.gravity = Gravity.CENTER_VERTICAL
        val pad = ctx.resources.getDimensionPixelSize(R.dimen.items_panel_row_padding)
        val gap = ctx.resources.getDimensionPixelSize(R.dimen.items_panel_row_v_gap)
        tv.setPadding(pad, pad, pad, pad)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        lp.topMargin = gap
        tv.layoutParams = lp
        return tv
    }

}
