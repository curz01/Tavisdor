package com.tavisdor.app.items

import com.tavisdor.app.enemies.Element

/**
 * Maps combat elements to the potency-1 shard consumed by elemental
 * arrow skills. Cluster / core conversion rates live on [Ingredient].
 */
object ElementalShards {

    fun shardFor(element: Element): Ingredient? = when (element) {
        Element.FIRE -> Ingredient.FLAME_SHARD
        Element.EARTH -> Ingredient.STONE_SHARD
        Element.AIR -> Ingredient.WIND_SHARD
        Element.WATER -> Ingredient.HYDRO_SHARD
        Element.NEUTRAL -> null
    }
}
