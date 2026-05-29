package com.tavisdor.app.items

/**
 * Mana potion crafted from a reagent via Make Potion. [ingredientPotency]
 * is the reagent tier consumed (1–3) and feeds the restore formula when
 * the potion is drunk.
 */
data class Potion(
    val ingredientPotency: Int,
) {
    val displayName: String = DISPLAY_NAME

    companion object {
        const val DISPLAY_NAME: String = "Potion"
    }
}
