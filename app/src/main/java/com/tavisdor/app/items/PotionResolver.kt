package com.tavisdor.app.items

import com.tavisdor.app.party.Hero
import kotlin.math.ceil
import kotlin.random.Random

object PotionResolver {

    /**
     * MP restored: 1d3 + ceil(hero level / 3) + [ingredientPotency].
     */
    fun mpRestoreAmount(hero: Hero, ingredientPotency: Int, rng: Random): Int {
        val dice = rng.nextInt(1, 4)
        val levelBonus = ceil(hero.level / 3.0).toInt()
        return dice + levelBonus + ingredientPotency.coerceAtLeast(1)
    }
}
