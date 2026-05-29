package com.tavisdor.app.dungeon

import com.tavisdor.app.items.Ingredient
import com.tavisdor.app.items.IngredientCategory
import com.tavisdor.app.items.LootDrop
import com.tavisdor.app.items.LootTier
import com.tavisdor.app.items.WeaponType
import kotlin.random.Random

/**
 * Rolls treasure-chest contents for dungeon depths 1–9 per the design table.
 * Deeper floors reuse the depth-9 table until higher bands are authored.
 */
object ChestLootRoller {

    fun roll(depth: Int, rng: Random): ChestLoot {
        val effectiveDepth = depth.coerceAtMost(9)
        val branch = rng.nextFloat()
        return when {
            branch < 0.05f -> ChestLoot()
            branch < 0.60f -> ChestLoot(
                gold = rng.nextInt(10, 16),
                items = mutableListOf(
                    randomArmor(effectiveDepth),
                    randomIngredient(effectiveDepth, rng),
                    randomShard(rng),
                    randomShard(rng),
                ),
            )
            else -> ChestLoot(
                gold = rng.nextInt(15, 21),
                items = mutableListOf(
                    randomWeapon(effectiveDepth, rng),
                    randomIngredient(effectiveDepth, rng),
                    randomIngredient(effectiveDepth, rng),
                ),
            )
        }
    }

    private fun randomArmor(depth: Int): LootDrop.ArmorDrop {
        val tier = LootTier.forDepth(depth)
        return LootDrop.ArmorDrop(tier.armorName)
    }

    private fun randomWeapon(depth: Int, rng: Random): LootDrop.MeleeWeaponDrop {
        val weapon = WeaponType.MELEE_TYPES.random(rng)
        val tier = LootTier.forDepth(depth)
        return LootDrop.MeleeWeaponDrop(weapon, tier)
    }

    private fun randomIngredient(depth: Int, rng: Random): LootDrop.IngredientDrop {
        val potency = ingredientPotencyForDepth(depth)
        val category = IngredientCategory.entries.random(rng)
        val pick = Ingredient.inCategory(category).firstOrNull { it.potency == potency }
            ?: Ingredient.STONE_SHARD
        return LootDrop.IngredientDrop(pick)
    }

    private fun randomShard(rng: Random): LootDrop.IngredientDrop {
        val candidates = Ingredient.elementalAtPotency(1)
        val pick = if (candidates.isEmpty()) Ingredient.STONE_SHARD else candidates.random(rng)
        return LootDrop.IngredientDrop(pick)
    }

    private fun ingredientPotencyForDepth(depth: Int): Int = when {
        depth <= 3 -> 1
        depth <= 6 -> 2
        else -> 3
    }
}
