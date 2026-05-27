package com.tavisdor.app.items

import kotlin.random.Random

/**
 * One independent drop-roll row inside a [LootTable]. Each entry's
 * [chance] is rolled in isolation - a kill can yield multiple drops
 * from the same table when multiple entries succeed on the same
 * corpse.
 *
 * Sealed so [LootTable.rollAll] can dispatch without reflection and
 * new drop kinds (Bow / Armor / Orb / Gold-as-loot, etc.) only
 * have to be added in one place.
 */
sealed class LootEntry {
    /** Probability in `[0f, 1f]`. 0.5f == 50% per kill. */
    abstract val chance: Float

    /**
     * Performs the per-entry roll. Returns the dropped item, or
     * null if the entry didn't fire. Pure function of [rng] and
     * [dungeonDepth]; no enemy / floor state is read.
     */
    abstract fun roll(rng: Random, dungeonDepth: Int): LootDrop?

    /**
     * "Drop a random Level [potency] ingredient with probability
     * [chance]." Resolution per the design choice
     * `category_then_item`:
     *   1. Roll [chance] - bail out on miss.
     *   2. Pick one of the four [IngredientCategory] values
     *      uniformly (25% each).
     *   3. Return the ingredient at [potency] within that category.
     *
     * Step 2 / 3 are equivalent to picking uniformly from
     * [Ingredient.atPotency] today (each category has exactly one
     * item per potency), but keeping the two-step structure means
     * future asymmetric categories don't change the chance math.
     */
    data class RandomIngredient(
        override val chance: Float,
        val potency: Int,
    ) : LootEntry() {
        override fun roll(rng: Random, dungeonDepth: Int): LootDrop? {
            if (rng.nextFloat() >= chance) return null
            val category = IngredientCategory.entries.random(rng)
            val pick = Ingredient.inCategory(category).firstOrNull { it.potency == potency }
                ?: return null
            return LootDrop.IngredientDrop(pick)
        }
    }

    /**
     * "Drop a random melee weapon with probability [chance]."
     *   1. Roll [chance] - bail out on miss.
     *   2. Pick uniformly from [WeaponType.MELEE_TYPES].
     *   3. Tier = [LootTier.forDepth] of the floor the kill
     *      happened on, so deeper floors drop better materials.
     */
    data class RandomMeleeWeapon(
        override val chance: Float,
    ) : LootEntry() {
        override fun roll(rng: Random, dungeonDepth: Int): LootDrop? {
            if (rng.nextFloat() >= chance) return null
            val weapon = WeaponType.MELEE_TYPES.random(rng)
            val tier = LootTier.forDepth(dungeonDepth)
            return LootDrop.MeleeWeaponDrop(weapon, tier)
        }
    }

    /** Drops a specific [ingredient] with probability [chance]. */
    data class FixedIngredient(
        override val chance: Float,
        val ingredient: Ingredient,
    ) : LootEntry() {
        override fun roll(rng: Random, dungeonDepth: Int): LootDrop? {
            if (rng.nextFloat() >= chance) return null
            return LootDrop.IngredientDrop(ingredient)
        }
    }

    /**
     * Drops a random elemental item at [potency] (1 = shard,
     * 2 = cluster, 3 = core) from the Flame / Stone / Wind / Hydro
     * families.
     */
    data class RandomElementalShard(
        override val chance: Float,
        val potency: Int = 1,
    ) : LootEntry() {
        override fun roll(rng: Random, dungeonDepth: Int): LootDrop? {
            if (rng.nextFloat() >= chance) return null
            val candidates = Ingredient.elementalAtPotency(potency)
            if (candidates.isEmpty()) return null
            return LootDrop.IngredientDrop(candidates.random(rng))
        }
    }
}
