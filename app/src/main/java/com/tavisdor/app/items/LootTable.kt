package com.tavisdor.app.items

import kotlin.random.Random

/**
 * A named collection of independent [LootEntry] rolls. Each kill
 * resolves the table once via [rollAll], which evaluates every
 * entry against an independent RNG draw and returns all drops that
 * landed.
 *
 * Tables are immutable and shareable - multiple
 * [com.tavisdor.app.enemies.EnemyTemplate]s can reference the same
 * [LootTableCatalog] entry (e.g. "spear_goblin", "club_goblin"
 * sharing "goblin_common"). Composition happens at the catalog
 * level, not by mutating tables.
 */
data class LootTable(
    val id: String,
    val entries: List<LootEntry>,
) {
    /**
     * Returns every drop that landed for one kill. Empty list when
     * no entry fired. Entries are evaluated in declaration order;
     * order doesn't affect the result today (no entry reads
     * sibling outcomes) but keep it stable so future weighted
     * entries can rely on it.
     */
    fun rollAll(rng: Random, dungeonDepth: Int): List<LootDrop> {
        if (entries.isEmpty()) return emptyList()
        val out = ArrayList<LootDrop>(entries.size)
        for (entry in entries) {
            entry.roll(rng, dungeonDepth)?.let { out += it }
        }
        return out
    }
}
