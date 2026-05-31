package com.tavisdor.app.items

/**
 * Central registry of every named [LootTable]. Enemies reference
 * tables by the string id stored on their
 * [com.tavisdor.app.enemies.EnemyTemplate]; this catalog resolves
 * those strings into concrete tables at kill time.
 *
 * Adding a new table:
 *   1. Author the [LootTable] entry in [AUTHORED] below.
 *   2. Make sure [LootTable.id] matches the enemy's
 *      `lootTableId` field.
 *
 * Duplicate ids fail-fast at JVM init (mirrors
 * [com.tavisdor.app.enemies.EnemyCatalog]) so a copy-paste mistake
 * doesn't silently shadow an existing table.
 */
object LootTableCatalog {

    private val AUTHORED: List<LootTable> = listOf(
        // ----- Spear Goblin -----
        // From the design chart:
        //   50% chance to drop a Level 1 random ingredient.
        //   50% chance to drop a Stone Shard (Poison Arrow reagent).
        //   40% chance to drop a (random) melee weapon at the
        //   floor's current material tier.
        //   10% chance to drop a +1 melee weapon (+1 attack over tier base).
        // Rolls are independent.
        LootTable(
            id = "spear_goblin",
            entries = listOf(
                LootEntry.RandomIngredient(chance = 0.50f, potency = 1),
                LootEntry.FixedIngredient(chance = 0.50f, ingredient = Ingredient.STONE_SHARD),
                LootEntry.RandomMeleeWeapon(chance = 0.40f),
                LootEntry.RandomMeleeWeapon(chance = 0.10f, plusLevel = 1),
            ),
        ),
        // ----- Bow Goblin -----
        LootTable(
            id = "bow_goblin",
            entries = listOf(
                LootEntry.RandomIngredient(chance = 0.50f, potency = 1),
                LootEntry.RandomBowOrStaff(chance = 0.35f),
                LootEntry.FixedIngredient(chance = 0.50f, ingredient = Ingredient.WIND_SHARD),
                LootEntry.RandomBowOrStaff(chance = 0.10f, plusLevel = 1),
            ),
        ),
        // ----- Bat -----
        LootTable(
            id = "bat",
            entries = listOf(
                LootEntry.RandomIngredientStack(
                    chance = 0.80f,
                    ingredient = Ingredient.WIND_SHARD,
                    minCount = 1,
                    maxCount = 2,
                ),
                LootEntry.RandomArmor(chance = 0.25f),
                LootEntry.RandomArmor(chance = 0.05f, plusLevel = 1),
            ),
        ),
    )

    private val byId: Map<String, LootTable> =
        AUTHORED.associateBy { it.id }.also { map ->
            check(map.size == AUTHORED.size) {
                val dupes = AUTHORED.groupBy { it.id }.filter { it.value.size > 1 }.keys
                "Duplicate loot table id(s) in LootTableCatalog.AUTHORED: $dupes"
            }
        }

    /** Look up a table by [id], or null if no such table is authored. */
    fun get(id: String): LootTable? = byId[id]

    /**
     * Look up a table by [id], throwing if it doesn't exist. Use
     * at call sites that just consumed an authored enemy whose
     * `lootTableId` is supposed to be in lock-step with this
     * catalog.
     */
    fun require(id: String): LootTable =
        byId[id] ?: error("No LootTable authored for id='$id'.")

    /** Every authored table, in insertion order. */
    fun all(): List<LootTable> = AUTHORED
}
