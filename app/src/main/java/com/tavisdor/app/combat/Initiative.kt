package com.tavisdor.app.combat

/** One slot in a combat's turn order. */
data class InitiativeEntry(
    val kind: Kind,
    /** Index into the party's hero list or the encounter's monster list, depending on [kind]. */
    val index: Int,
    val dexterity: Int,
) {
    enum class Kind { HERO, MONSTER }
}

/**
 * Builds a per-encounter turn order sorted by dexterity descending across all
 * 4 heroes and every monster in the room. Ties are resolved by original
 * insertion order - heroes first (so on a tie a hero acts before a monster).
 * Final tie-break rule can be tuned once combat math is defined.
 */
object Initiative {
    fun build(heroDex: List<Int>, monsterDex: List<Int>): List<InitiativeEntry> {
        val entries = buildList {
            heroDex.forEachIndexed { i, dex ->
                add(InitiativeEntry(InitiativeEntry.Kind.HERO, i, dex))
            }
            monsterDex.forEachIndexed { i, dex ->
                add(InitiativeEntry(InitiativeEntry.Kind.MONSTER, i, dex))
            }
        }
        return entries.sortedByDescending { it.dexterity }
    }
}
