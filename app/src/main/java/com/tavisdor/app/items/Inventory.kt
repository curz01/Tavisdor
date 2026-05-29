package com.tavisdor.app.items

import com.tavisdor.app.party.Party

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
    private val _potions: MutableList<Potion> = mutableListOf()
    private val _floorKeys: MutableList<FloorKey> = mutableListOf()
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

    /** Crafted mana potions (one list entry per physical potion). */
    val potions: List<Potion> get() = _potions

    /** Keys tied to a specific lock on a specific dungeon floor depth. */
    val floorKeys: List<FloorKey> get() = _floorKeys

    /**
     * Items waiting in the pickup window. Indexing is stable
     * between [pickUpAt] calls so the activity can pass the
     * tapped row's index back unchanged.
     */
    val pendingPickup: List<LootDrop> get() = _pendingPickup

    /** True when there's at least one row in [pendingPickup]. */
    val hasPendingPickup: Boolean get() = _pendingPickup.isNotEmpty()

    val maxSlotsPerTab: Int get() = InventoryCapacity.SLOTS_PER_TAB

    val usedEquipmentSlots: Int get() = _weapons.size

    val usedMaterialsSlots: Int
        get() = _ingredients.size + _potions.size + _floorKeys.size

    val usedPickupSlots: Int get() = _pendingPickup.size

    val freeEquipmentSlots: Int get() = (maxSlotsPerTab - usedEquipmentSlots).coerceAtLeast(0)

    val freeMaterialsSlots: Int get() = (maxSlotsPerTab - usedMaterialsSlots).coerceAtLeast(0)

    val freePickupSlots: Int
        get() = (maxSlotsPerTab - usedPickupSlots).coerceAtLeast(0)

    /** Index of the first bag row matching [weapon] (data-class equality). */
    fun indexOfWeapon(weapon: Weapon): Int =
        _weapons.indexOfFirst { it == weapon }

    /** Index of the first bag row with this display name (stack pick). */
    fun indexOfWeaponByDisplayName(displayName: String): Int =
        _weapons.indexOfFirst { it.displayName == displayName }

    /** Removes and returns the weapon at [index], or null if out of range. */
    fun removeWeaponAt(index: Int): Weapon? {
        if (index !in _weapons.indices) return null
        val removed = _weapons.removeAt(index)
        notifyChanged()
        return removed
    }

    /** Adds a spare weapon to the Gear stash. Returns false when full. */
    fun addWeapon(weapon: Weapon): Boolean {
        if (usedEquipmentSlots >= maxSlotsPerTab) return false
        _weapons += weapon
        notifyChanged()
        return true
    }

    /** How many of [ingredient] are in the stash (duplicates count). */
    fun countOf(ingredient: Ingredient): Int =
        _ingredients.count { it == ingredient }

    fun hasIngredient(ingredient: Ingredient): Boolean =
        countOf(ingredient) > 0

    /**
     * Removes one instance of [ingredient] from the stash. Returns
     * false when none are available.
     */
    fun consumeIngredient(ingredient: Ingredient): Boolean {
        val index = _ingredients.indexOf(ingredient)
        if (index < 0) return false
        _ingredients.removeAt(index)
        notifyChanged()
        return true
    }

    fun addIngredient(ingredient: Ingredient): Boolean {
        if (usedMaterialsSlots >= maxSlotsPerTab) return false
        _ingredients += ingredient
        notifyChanged()
        return true
    }

    fun addPotion(potion: Potion): Boolean {
        if (usedMaterialsSlots >= maxSlotsPerTab) return false
        _potions += potion
        notifyChanged()
        return true
    }

    /**
     * Removes and returns the first crafted potion, or null when the
     * stash is empty.
     */
    fun consumeFirstPotion(): Potion? {
        if (_potions.isEmpty()) return null
        val removed = _potions.removeAt(0)
        notifyChanged()
        return removed
    }

    fun hasFloorKey(floorDepth: Int, lockId: String): Boolean =
        _floorKeys.any { it.floorDepth == floorDepth && it.lockId == lockId }

    fun consumeFloorKey(floorDepth: Int, lockId: String): Boolean {
        val index = _floorKeys.indexOfFirst {
            it.floorDepth == floorDepth && it.lockId == lockId
        }
        if (index < 0) return false
        _floorKeys.removeAt(index)
        notifyChanged()
        return true
    }

    fun addFloorKey(key: FloorKey): Boolean {
        if (usedMaterialsSlots >= maxSlotsPerTab) return false
        _floorKeys += key
        notifyChanged()
        return true
    }

    fun canQueuePickup(drop: LootDrop): Boolean =
        freePickupSlots > 0

    /** Deposits one loot drop from a chest (or other direct source) into the bag. */
    fun tryDepositLoot(drop: LootDrop, party: Party): Boolean = depositPickup(drop, party)

    fun canDeposit(drop: LootDrop, party: Party? = null): Boolean = when (drop) {
        is LootDrop.MeleeWeaponDrop -> freeEquipmentSlots > 0
        is LootDrop.IngredientDrop,
        is LootDrop.FloorKeyDrop,
        -> freeMaterialsSlots > 0
        is LootDrop.ArmorDrop -> party?.heroes?.any { it.isAlive && it.armor == null } == true
    }

    /** Pushes [drop] onto the back of the pickup queue. Returns false when the loot tab is full. */
    fun queuePickup(drop: LootDrop): Boolean {
        if (!canQueuePickup(drop)) return false
        _pendingPickup += drop
        notifyChanged()
        return true
    }

    /** Convenience for combat: appends every entry of [drops] that fits. */
    fun queueAllPickups(drops: List<LootDrop>): Int {
        if (drops.isEmpty()) return 0
        var queued = 0
        for (drop in drops) {
            if (!queuePickup(drop)) break
            queued++
        }
        return queued
    }

    /**
     * Moves the pickup row at [index] into the correct destination
     * list ([weapons] / [ingredients]) and removes it from
     * [pendingPickup]. Returns the picked-up drop, or null when
     * [index] is out of range (e.g. stale UI tap after a race).
     */
    fun pickUpAt(index: Int, party: Party? = null): LootDrop? {
        val drop = _pendingPickup.getOrNull(index) ?: return null
        if (!depositPickup(drop, party)) return null
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
    fun pickUpAll(party: Party? = null): List<LootDrop> {
        if (_pendingPickup.isEmpty()) return emptyList()
        val collected = ArrayList<LootDrop>(_pendingPickup.size)
        val remaining = ArrayList<LootDrop>()
        for (drop in _pendingPickup) {
            if (depositPickup(drop, party)) {
                collected += drop
            } else {
                remaining += drop
            }
        }
        _pendingPickup.clear()
        _pendingPickup += remaining
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
    private fun depositPickup(drop: LootDrop, party: Party? = null): Boolean = when (drop) {
        is LootDrop.IngredientDrop -> addIngredient(drop.ingredient)
        is LootDrop.MeleeWeaponDrop -> addWeapon(weaponFromDrop(drop))
        is LootDrop.FloorKeyDrop -> addFloorKey(drop.key)
        is LootDrop.ArmorDrop -> {
            val hero = party?.heroes?.firstOrNull { it.isAlive && it.armor == null }
            if (hero == null) {
                false
            } else {
                hero.armor = drop.armorName
                notifyChanged()
                true
            }
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
    fun restore(
        weapons: List<Weapon>,
        ingredients: List<Ingredient>,
        potions: List<Potion> = emptyList(),
        floorKeys: List<FloorKey> = emptyList(),
    ) {
        _weapons.clear()
        _weapons += weapons
        _ingredients.clear()
        _ingredients += ingredients
        _potions.clear()
        _potions += potions
        _floorKeys.clear()
        _floorKeys += floorKeys
        _pendingPickup.clear()
        notifyChanged()
    }
}
