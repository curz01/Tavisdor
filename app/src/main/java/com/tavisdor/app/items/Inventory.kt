package com.tavisdor.app.items

/**
 * Per-party storage for everything the heroes carry that ISN'T
 * equipped on a hero directly. Three logical buckets:
 *
 *   1. [weapons]        - spare melee weapons + future ranged /
 *                         armor entries. The currently-wielded
 *                         weapon still lives on [com.tavisdor.app.party.Hero.weapon1].
 *   2. [ingredients]    - crafting / consumable ingredient stack.
 *                         Duplicates are stored as repeated entries
 *                         rather than a `Map<Ingredient, Int>` so
 *                         the UI can render them as individual
 *                         rows (matches the per-row pickup model).
 *   3. [pendingPickup]  - drops queued for the player to either
 *                         pick up or discard via the items panel.
 *                         Populated by combat at victory and (later)
 *                         by chest / container interactions; flushed
 *                         when the player closes the panel with the
 *                         pickup section non-empty.
 *
 * Mutable lists are exposed via read-only views; mutations go
 * through the dedicated [queuePickup] / [pickUpAt] / [pickUpAll] /
 * [discardPendingPickup] helpers so the panel UI and combat
 * controller don't accidentally bypass the rule that the pickup
 * queue is the ONLY thing that gets thrown out on close.
 */
class Inventory {

    private val _weapons: MutableList<Weapon> = mutableListOf()
    private val _ingredients: MutableList<Ingredient> = mutableListOf()
    private val _pendingPickup: MutableList<LootDrop> = mutableListOf()

    /**
     * Fired after every mutation that changes any of the three
     * buckets. The items panel hooks this so a kill-drop or
     * pickup re-renders the panel without the activity having
     * to know which method was called.
     *
     * Also fired by [com.tavisdor.app.party.Party.addGold] (via
     * [notifyOwnerChanged]) so gold updates re-render the same
     * panel through the same subscription.
     */
    var onChanged: (() -> Unit)? = null

    private fun notifyChanged() {
        onChanged?.invoke()
    }

    /**
     * Public alias used by the inventory's owner ([Party]) when
     * non-inventory state that the panel still cares about (e.g.
     * the gold counter) changes. Keeps the panel's subscription
     * surface to a single callback.
     */
    fun notifyOwnerChanged() {
        notifyChanged()
    }

    /** Spare weapons - everything not currently equipped on a hero. */
    val weapons: List<Weapon> get() = _weapons

    /**
     * Ingredient stash. Duplicates intended; each entry is one
     * physical item, so the UI can render and pick them up one
     * at a time even when stacks land.
     */
    val ingredients: List<Ingredient> get() = _ingredients

    /**
     * Items waiting in the pickup window. Indexing is stable
     * between [pickUpAt] calls so the activity can pass the
     * tapped row's index back unchanged.
     */
    val pendingPickup: List<LootDrop> get() = _pendingPickup

    /** True when there's at least one row in [pendingPickup]. */
    val hasPendingPickup: Boolean get() = _pendingPickup.isNotEmpty()

    /** Pushes [drop] onto the back of the pickup queue. */
    fun queuePickup(drop: LootDrop) {
        _pendingPickup += drop
        notifyChanged()
    }

    /** Convenience for combat: appends every entry of [drops] in order. */
    fun queueAllPickups(drops: List<LootDrop>) {
        if (drops.isEmpty()) return
        _pendingPickup += drops
        notifyChanged()
    }

    /**
     * Moves the pickup row at [index] into the correct destination
     * list ([weapons] / [ingredients]) and removes it from
     * [pendingPickup]. Returns the picked-up drop, or null when
     * [index] is out of range (e.g. stale UI tap after a race).
     */
    fun pickUpAt(index: Int): LootDrop? {
        val drop = _pendingPickup.getOrNull(index) ?: return null
        depositPickup(drop)
        _pendingPickup.removeAt(index)
        notifyChanged()
        return drop
    }

    /**
     * Bulk variant of [pickUpAt]: drains every entry in
     * [pendingPickup] into the destination lists in declaration
     * order. Returns the list of drops that were collected so
     * the caller can log / animate them.
     */
    fun pickUpAll(): List<LootDrop> {
        if (_pendingPickup.isEmpty()) return emptyList()
        val collected = _pendingPickup.toList()
        for (drop in collected) depositPickup(drop)
        _pendingPickup.clear()
        notifyChanged()
        return collected
    }

    /**
     * Discards every pickup row without depositing anything.
     * Called when the player closes the items panel with the
     * pickup section non-empty. Returns the count discarded.
     */
    fun discardPendingPickup(): Int {
        if (_pendingPickup.isEmpty()) return 0
        val count = _pendingPickup.size
        _pendingPickup.clear()
        notifyChanged()
        return count
    }

    /**
     * Routes a single drop into the right destination list based
     * on its variant. Centralized so [pickUpAt] and [pickUpAll]
     * stay in lock-step when a new [LootDrop] subtype lands.
     */
    private fun depositPickup(drop: LootDrop) {
        when (drop) {
            is LootDrop.IngredientDrop -> _ingredients += drop.ingredient
            is LootDrop.MeleeWeaponDrop -> _weapons += weaponFromDrop(drop)
        }
    }

    /**
     * Materializes a tiered melee [LootDrop.MeleeWeaponDrop] into
     * a concrete [Weapon]. Display name + attack bonus are pulled
     * from the [LootTier] chart; range is the standard melee
     * reach (1) since [LootEntry.RandomMeleeWeapon] only rolls
     * melee weapons today. Bow / ranged drops will need their
     * own branch when those entries are authored.
     */
    private fun weaponFromDrop(drop: LootDrop.MeleeWeaponDrop): Weapon {
        val type = drop.weapon
        val tier = drop.tier
        return Weapon(
            type = type,
            tier = tier,
            displayName = tier.displayMeleeName(type),
            attackBonus = tier.meleeWeaponBaseDamage,
            range = Weapon.CRUDE_MELEE_RANGE,
        )
    }

    // ----- Save / load hooks (in-memory only; persistence path
    // lives on SaveStore) -----

    /**
     * Replaces the persistent buckets ([weapons] + [ingredients])
     * with the supplied lists. Used by the load path to rehydrate
     * a previous session's inventory. The pickup queue is NOT
     * restored - it's transient by design (closed-panel ==
     * discarded).
     */
    fun restore(weapons: List<Weapon>, ingredients: List<Ingredient>) {
        _weapons.clear()
        _weapons += weapons
        _ingredients.clear()
        _ingredients += ingredients
        _pendingPickup.clear()
        notifyChanged()
    }
}
