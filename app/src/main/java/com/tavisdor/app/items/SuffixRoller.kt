package com.tavisdor.app.items

import com.tavisdor.app.enemies.Element
import kotlin.random.Random

/**
 * Rolls how many suffixes an item receives at [depth] and picks
 * distinct [ItemSuffixKind] entries. Prefix tier / attack damage
 * are handled separately by [LootTier].
 */
object SuffixRoller {

    fun rollSuffixes(depth: Int, rng: Random): List<ItemSuffix> {
        val count = rollSuffixCount(depth, rng)
        if (count <= 0) return emptyList()
        val potency = suffixPotencyForDepth(depth)
        val kinds = mutableSetOf<ItemSuffixKind>()
        val out = ArrayList<ItemSuffix>(count)
        while (out.size < count) {
            val kind = ItemSuffixKind.entries.random(rng)
            if (!kinds.add(kind)) continue
            val placement = if (rng.nextBoolean()) SuffixPlacement.MIDDLE else SuffixPlacement.OF
            val element = if (kind.needsElement) {
                Element.entries.filter { it != Element.NEUTRAL }.random(rng)
            } else {
                null
            }
            out += ItemSuffix(kind, potency, placement, element)
        }
        return out
    }

    /** N scales with loot tier at the floor depth where the item dropped. */
    fun suffixPotencyForDepth(depth: Int): Int =
        LootTier.forDepth(depth).meleeWeaponBaseDamage.coerceAtLeast(1)

    /**
     * Depth bands from the suffix chart. Probabilities are independent
     * rolls unless noted as guaranteed minimums.
     */
    fun rollSuffixCount(depth: Int, rng: Random): Int = when {
        depth <= 3 -> 0
        depth <= 8 -> if (rng.nextInt(100) < 1) 1 else 0
        depth <= 12 -> if (rng.nextInt(100) < 10) 1 else 0
        depth <= 16 -> rollTwoTierSuffixCount(rng, firstPct = 50, secondPct = 5)
        depth <= 20 -> rollTwoTierSuffixCount(rng, firstPct = 100, secondPct = 25)
        depth <= 24 -> rollTwoTierSuffixCount(rng, firstPct = 100, secondPct = 50)
        depth <= 28 -> rollTwoTierSuffixCount(rng, firstPct = 100, secondPct = 75)
        depth <= 32 -> rollGuaranteedWithExtra(rng, guaranteed = 2, extraPct = 10, extraCount = 1)
        depth <= 36 -> rollGuaranteedWithExtra(rng, guaranteed = 2, extraPct = 25, extraCount = 1)
        depth <= 40 -> rollGuaranteedWithExtra(rng, guaranteed = 2, extraPct = 50, extraCount = 1)
        else -> {
            var count = 2
            if (rng.nextInt(100) < 75) count = 3
            if (count >= 3 && rng.nextInt(100) < 10) count = 4
            count
        }
    }

    private fun rollTwoTierSuffixCount(rng: Random, firstPct: Int, secondPct: Int): Int {
        if (firstPct < 100 && rng.nextInt(100) >= firstPct) return 0
        var count = 1
        if (rng.nextInt(100) < secondPct) count = 2
        return count
    }

    private fun rollGuaranteedWithExtra(
        rng: Random,
        guaranteed: Int,
        extraPct: Int,
        extraCount: Int,
    ): Int {
        var count = guaranteed
        if (rng.nextInt(100) < extraPct) count += extraCount
        return count
    }
}
