package com.tavisdor.app.ui

import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.tavisdor.app.R
import com.tavisdor.app.items.Ingredient
import com.tavisdor.app.items.Inventory
import com.tavisdor.app.items.LootDrop
import com.tavisdor.app.items.Weapon
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
 *   - Section 1 (Equipment & Weapons): read-only list of [Weapon].
 *   - Section 2 (Ingredients): read-only list of [Ingredient].
 *   - Section 3 (Pickup / Discard): tappable rows that move
 *     into section 1 / 2 on tap, or get silently dropped when
 *     the panel closes with the section non-empty. "Pick Up
 *     All" button below grabs the entire queue at once.
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
    private val ctx: Context get() = root.context

    private val scroll: ScrollView = root.findViewById(R.id.itemsPanelScroll)
    private val panel: View = root.findViewById(R.id.itemsPanel)

    private val tvGold: TextView = root.findViewById(R.id.tvItemsGold)
    private val equipmentList: LinearLayout = root.findViewById(R.id.itemsEquipmentList)
    private val ingredientList: LinearLayout = root.findViewById(R.id.itemsIngredientList)
    private val pickupList: LinearLayout = root.findViewById(R.id.itemsPickupList)
    private val tvPickupHint: TextView = root.findViewById(R.id.tvItemsPickupHint)
    private val btnClose: MaterialButton = root.findViewById(R.id.btnItemsClose)
    private val btnPickUpAll: MaterialButton = root.findViewById(R.id.btnItemsPickUpAll)

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
        panel.setOnClickListener { /* consume so taps don't dismiss */ }
        btnClose.setOnClickListener { hide() }
        btnPickUpAll.setOnClickListener { onPickUpAllTapped() }
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
        refresh()
        scroll.scrollTo(0, 0)
        root.visibility = View.VISIBLE
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
        val discarded = party?.inventory?.discardPendingPickup() ?: 0
        party?.inventory?.onChanged = null
        root.visibility = View.GONE
        if (discarded > 0) {
            Toast.makeText(
                ctx,
                ctx.getString(R.string.items_panel_toast_discarded, discarded),
                Toast.LENGTH_SHORT,
            ).show()
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
        Toast.makeText(
            ctx,
            ctx.getString(R.string.items_panel_toast_picked_up, label),
            Toast.LENGTH_SHORT,
        ).show()
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
        renderIngredients(p.inventory.ingredients)
        renderPickup(p.inventory.pendingPickup)
    }

    private fun renderEquipment(weapons: List<Weapon>) {
        equipmentList.removeAllViews()
        if (weapons.isEmpty()) {
            equipmentList.addView(emptyRow())
            return
        }
        for (group in groupByLabel(weapons) { it.displayName }) {
            equipmentList.addView(staticRow(formatStackLabel(group.label, group.count)))
        }
    }

    private fun renderIngredients(ingredients: List<Ingredient>) {
        ingredientList.removeAllViews()
        if (ingredients.isEmpty()) {
            ingredientList.addView(emptyRow())
            return
        }
        for (group in groupByLabel(ingredients) { it.displayName }) {
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
        Toast.makeText(
            ctx,
            ctx.getString(R.string.items_panel_toast_picked_up, displayName(picked)),
            Toast.LENGTH_SHORT,
        ).show()
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
