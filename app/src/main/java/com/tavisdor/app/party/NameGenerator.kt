package com.tavisdor.app.party

import kotlin.random.Random

/**
 * Picks short placeholder names for freshly created heroes. Names are deliberately
 * generic (no class-specific flavour) and capped at 6 characters so they read
 * cleanly in the per-hero "Name - Class" label in [com.tavisdor.app.HeroPanelView]
 * without truncation on narrow screens.
 *
 * Replace the [pool] with per-culture / per-class name tables later; the rest of
 * the project just calls [pickUnique] so the swap is transparent.
 */
object NameGenerator {

    /** All entries MUST be 1..6 characters. The init block enforces this at load. */
    private val pool: List<String> = listOf(
        "Aldo", "Bran", "Cale", "Dax", "Eli", "Faye",
        "Gus", "Hale", "Iris", "Jax", "Kira", "Lyra",
        "Mira", "Niko", "Orin", "Pax", "Quin", "Roan",
        "Sage", "Tara", "Vex", "Wyn", "Yara", "Zara",
        "Inez", "Reno", "Drex", "Bram", "Riven", "Sera",
        "Thane", "Mox", "Ren", "Nia", "Vega", "Nova",
        "Pip", "Lior", "Wren", "Mel",
    )

    init {
        check(pool.all { it.length in 1..MAX_LEN }) {
            "All names in NameGenerator.pool must be 1..$MAX_LEN characters."
        }
    }

    /**
     * Returns [count] distinct names drawn uniformly at random from [pool].
     * Throws if [count] exceeds the pool size.
     */
    fun pickUnique(count: Int, random: Random = Random.Default): List<String> {
        require(count > 0) { "count must be positive." }
        require(count <= pool.size) {
            "Requested $count names but pool only has ${pool.size}."
        }
        return pool.shuffled(random).take(count)
    }

    /** Deterministic fallback (e.g. for loading legacy saves that lack a name field). */
    fun fallback(slotIndex: Int): String = "Hero${slotIndex + 1}"

    const val MAX_LEN: Int = 6
}
