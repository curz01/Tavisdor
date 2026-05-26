package com.tavisdor.app.party

import kotlin.random.Random

/**
 * Picks short placeholder names for freshly created heroes. Names are deliberately
 * generic (no class-specific flavour) and capped at 6 characters so they read
 * cleanly in the per-hero "Name - Class" label in [com.tavisdor.app.HeroPanelView]
 * without truncation on narrow screens.
 *
 * Two parallel pools - [malePool] and [femalePool] - so the name a slot
 * is assigned matches the gender pick on the class-select screen.
 * The legacy unisex entry point ([pickUnique]) draws from the male
 * pool so call sites that haven't yet been taught about gender
 * still produce a stable result.
 *
 * Replace these with per-culture / per-class name tables later; the rest of
 * the project just calls [pickFor] / [reroll] so the swap is transparent.
 */
object NameGenerator {

    /** All entries MUST be 1..6 characters. The init block enforces this at load. */
    private val malePool: List<String> = listOf(
        "Aldo", "Bran", "Cale", "Dax", "Eli", "Gus",
        "Hale", "Jax", "Niko", "Orin", "Pax", "Quin",
        "Roan", "Vex", "Inez", "Reno", "Drex", "Bram",
        "Riven", "Thane", "Mox", "Ren", "Lior", "Pip",
    )

    private val femalePool: List<String> = listOf(
        "Faye", "Iris", "Kira", "Lyra", "Mira", "Sage",
        "Tara", "Wyn", "Yara", "Zara", "Sera", "Nia",
        "Vega", "Nova", "Wren", "Mel", "Rhea", "Eira",
        "Luna", "Nyx", "Saga", "Cora", "Indra", "Esme",
    )

    init {
        check(malePool.all { it.length in 1..MAX_LEN }) {
            "All names in NameGenerator.malePool must be 1..$MAX_LEN characters."
        }
        check(femalePool.all { it.length in 1..MAX_LEN }) {
            "All names in NameGenerator.femalePool must be 1..$MAX_LEN characters."
        }
    }

    /**
     * Returns [count] distinct male-pool names drawn uniformly at
     * random. Kept for call sites that pre-date gender support; new
     * code should use [pickFor] so the names align with the player's
     * gender pick.
     */
    fun pickUnique(count: Int, random: Random = Random.Default): List<String> {
        require(count > 0) { "count must be positive." }
        require(count <= malePool.size) {
            "Requested $count names but male pool only has ${malePool.size}."
        }
        return malePool.shuffled(random).take(count)
    }

    /**
     * Returns a single random name from the matching pool. Used by
     * the class-select screen to rename a slot when the player
     * toggles its gender. [exclude] is consulted so the freshly-
     * drawn name doesn't collide with another slot in the party.
     *
     * Silently falls back to [fallback] if the matching pool is
     * exhausted after excluding the used names (impossible in
     * practice with a 4-slot party + 24+ names per pool, but keeps
     * the caller from having to handle an empty-list case).
     */
    fun pickFor(
        gender: Gender,
        exclude: Set<String> = emptySet(),
        slotIndex: Int = 0,
        random: Random = Random.Default,
    ): String {
        val pool = poolFor(gender).filter { it !in exclude }
        return if (pool.isEmpty()) fallback(slotIndex) else pool.random(random)
    }

    /**
     * Draws [count] distinct names where the i-th name comes from
     * the pool matching [genders][i]. Within a single party every
     * name is guaranteed unique across slots even when the same
     * gender appears multiple times. Used by the class-select reset
     * to seed all 4 slots with names that align with each slot's
     * initial gender pick.
     */
    fun pickUniqueByGender(
        genders: List<Gender>,
        random: Random = Random.Default,
    ): List<String> {
        require(genders.isNotEmpty()) { "genders must not be empty." }
        val taken = HashSet<String>()
        return genders.mapIndexed { idx, g ->
            val name = pickFor(g, exclude = taken, slotIndex = idx, random = random)
            taken.add(name)
            name
        }
    }

    /** Deterministic fallback (e.g. for loading legacy saves that lack a name field). */
    fun fallback(slotIndex: Int): String = "Hero${slotIndex + 1}"

    private fun poolFor(gender: Gender): List<String> = when (gender) {
        Gender.MALE -> malePool
        Gender.FEMALE -> femalePool
    }

    const val MAX_LEN: Int = 6
}
